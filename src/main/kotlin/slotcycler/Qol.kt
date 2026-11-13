package slotcycler

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket
import net.minecraft.registry.Registries
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.MerchantScreenHandler
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.ShulkerBoxScreenHandler
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Util
import slotcycler.Ui.AMBER
import slotcycler.Ui.CHIP_BG
import slotcycler.Ui.OK
import slotcycler.Ui.TEXT
import slotcycler.Ui.WARN
import slotcycler.Ui.alpha
import slotcycler.Ui.roundRect
import kotlin.math.roundToInt
import kotlin.math.sin

object Qol {
    private const val CLICKS_PER_TICK = 4
    private const val MAX_TRADES = 64
    private const val TRADE_WAIT = 3
    private const val PLAYER_SLOTS = 36
    private const val MAIN_SLOTS = 27
    private const val LOW = 0.10f
    private const val MID = 0.35f

    private const val ZOOM_FOV = 30
    private const val LOW_HEALTH = 6f
    private const val TRADE_LABEL = "Trade max"

    class Line(val icon: ItemStack, val text: String, val color: Int, val width: Int)

    enum class Kind(val label: String) {
        INFO("Info line"), EFFECTS("Effect timers"), ARMOR("Armour"), TRACKER("Item tracker")
    }

    class Box(val kind: Kind) {
        var lines: List<Line> = emptyList()
        var x = 0
        var y = 0
        var w = 0
        var h = 0
    }

    val boxes = Kind.entries.map { Box(it) }

    private val gear = arrayOf(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    )
    private val warned = BooleanArray(gear.size)
    private val clicks = ArrayDeque<Int>()
    private var clickSync = -1
    private var tradesLeft = 0
    private var tradeWait = 0
    private var tradesDone = 0
    private var age = 0
    private var wasDead = false
    private var bright = false

    private var zooming = false
    private var zoomFov = 70
    private var zoomSmooth = false
    private var tradeLabel = TRADE_LABEL
    private var infoLines: List<Line> = emptyList()
    private var armorLines: List<Line> = emptyList()
    private var trackerLines: List<Line> = emptyList()
    private var effectLines: List<Line> = emptyList()

    private val s get() = Settings.current

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
        HudElementRegistry.addLast(Identifier.of(SlotCyclerMod.MOD_ID, "hud"), ::renderHud)
        ScreenEvents.AFTER_INIT.register { _, screen, width, height -> decorate(screen, width, height) }
    }

    private fun tick(client: MinecraftClient) {
        val player = client.player
        if (player == null) {
            clicks.clear()
            tradesLeft = 0
            wasDead = false
            bright = false
            return
        }
        age++
        fullbright(player)
        deathCoords(player)
        if (age % 4 == 0) {
            refreshHud(client, player)
            refreshTrade(player)
        }
        if (age % 20 == 0 && s.durabilityAlert) durabilityAlert(player)
        runClicks(client, player)
        runTrades(client, player)
    }

    private fun fullbright(player: ClientPlayerEntity) {
        val current = player.getStatusEffect(StatusEffects.NIGHT_VISION)
        val ours = current != null && current.isAmbient && !current.shouldShowIcon()
        if (s.fullbright) {
            if (current == null || (ours && !current.isInfinite)) {
                player.addStatusEffect(
                    StatusEffectInstance(StatusEffects.NIGHT_VISION, StatusEffectInstance.INFINITE, 0, true, false, false)
                )
                bright = true
            }
        } else if (bright) {
            if (ours) player.removeStatusEffect(StatusEffects.NIGHT_VISION)
            bright = false
        }
    }

    private fun deathCoords(player: ClientPlayerEntity) {
        val dead = player.isDead
        if (dead && !wasDead && s.deathCoords) {
            val pos = player.blockPos
            player.sendMessage(
                Text.literal("Slot Cycler » ").styled { it.withColor(SlotCyclerMod.CYAN) }
                    .append(Text.literal("You died at ${pos.x}, ${pos.y}, ${pos.z}").styled { it.withColor(SlotCyclerMod.SOFT) }),
                false
            )
        }
        wasDead = dead
    }

    private fun durabilityAlert(player: ClientPlayerEntity) {
        for (i in gear.indices) {
            val stack = player.getEquippedStack(gear[i])
            val low = stack.isDamageable && (stack.maxDamage - stack.damage) <= stack.maxDamage * LOW
            if (low && !warned[i]) {
                SlotCyclerMod.announce(
                    player,
                    "${stack.name.string} is almost broken" to SlotCyclerMod.BAD,
                    "${stack.maxDamage - stack.damage} uses left" to SlotCyclerMod.SOFT
                )
                player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, 0.7f)
            }
            warned[i] = low
        }
    }

    private fun refreshHud(client: MinecraftClient, player: ClientPlayerEntity) {
        val font = client.textRenderer
        fun line(icon: ItemStack, text: String, color: Int) = Line(icon, text, color, font.getWidth(text))

        if (s.infoHud) {
            val pos = player.blockPos
            val ping = client.networkHandler?.getPlayerListEntry(player.uuid)?.latency ?: 0
            val facing = player.horizontalFacing.name.first()
            infoLines = listOf(line(ItemStack.EMPTY,
                "${pos.x} ${pos.y} ${pos.z}  ·  $facing  ·  $ping ms  ·  ${client.currentFps} fps", TEXT))
        } else infoLines = emptyList()

        armorLines = if (!s.armorHud) emptyList() else gear.mapNotNull { slot ->
            val stack = player.getEquippedStack(slot)
            if (!stack.isDamageable) return@mapNotNull null
            val left = stack.maxDamage - stack.damage
            val fraction = left.toFloat() / stack.maxDamage
            line(stack, left.toString(), if (fraction <= LOW) WARN else if (fraction <= MID) AMBER else TEXT)
        }

        trackerLines = if (!s.itemTracker) emptyList() else {
            val terms = s.trackedTerms()
            if (terms.isEmpty()) emptyList() else {
                val counts = IntArray(terms.size)
                val icons = arrayOfNulls<ItemStack>(terms.size)
                val inventory = player.inventory
                for (i in 0 until inventory.size()) {
                    val stack = inventory.getStack(i)
                    if (stack.isEmpty) continue
                    val id = Registries.ITEM.getId(stack.item).path
                    val name = stack.name.string.lowercase().replace(' ', '_')
                    for (t in terms.indices) {
                        if (terms[t] in id || terms[t] in name) {
                            counts[t] += stack.count
                            if (icons[t] == null) icons[t] = stack
                            break
                        }
                    }
                }
                terms.indices.mapNotNull { t -> icons[t]?.let { line(it, counts[t].toString(), TEXT) } }
            }
        }

        effectLines = if (!s.effectTimers) emptyList() else player.statusEffects
            .filter { it.shouldShowIcon() }
            .sortedBy { it.duration }
            .map { effect ->
                val level = if (effect.amplifier > 0) " ${effect.amplifier + 1}" else ""
                val seconds = effect.duration / 20
                val time = if (effect.isInfinite) "∞" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                val name = Text.translatable(effect.translationKey).string
                line(ItemStack.EMPTY, "$name$level  $time",
                    if (!effect.effectType.value().isBeneficial) WARN else if (seconds < 10 && !effect.isInfinite) AMBER else OK)
            }
    }

    fun position(kind: Kind): Settings.Pos = when (kind) {
        Kind.INFO -> s.posInfo
        Kind.EFFECTS -> s.posEffects
        Kind.ARMOR -> s.posArmor
        Kind.TRACKER -> s.posTracker
    } ?: Settings.Pos()

    fun sample(kind: Kind): List<Line> {
        val font = MinecraftClient.getInstance().textRenderer
        fun line(icon: ItemStack, text: String, color: Int) = Line(icon, text, color, font.getWidth(text))
        return when (kind) {
            Kind.INFO -> listOf(line(ItemStack.EMPTY, "128 64 -30  ·  N  ·  45 ms  ·  144 fps", TEXT))
            Kind.EFFECTS -> listOf(line(ItemStack.EMPTY, "Speed 2  1:15", OK), line(ItemStack.EMPTY, "Poison  0:16", WARN))
            Kind.ARMOR -> listOf(
                line(ItemStack(Items.IRON_HELMET), "15", WARN),
                line(ItemStack(Items.DIAMOND_CHESTPLATE), "228", TEXT),
                line(ItemStack(Items.DIAMOND_SWORD), "512", AMBER)
            )
            Kind.TRACKER -> listOf(
                line(ItemStack(Items.NETHERITE_SCRAP), "23", TEXT),
                line(ItemStack(Items.TOTEM_OF_UNDYING), "1", TEXT),
                line(ItemStack(Items.GOLDEN_APPLE), "5", TEXT)
            )
        }
    }

    fun layout(box: Box, lines: List<Line>, width: Int, height: Int) {
        box.lines = lines
        val widest = lines.maxOfOrNull { it.width } ?: 0
        when (box.kind) {
            Kind.INFO -> { box.w = widest + 8; box.h = 14 }
            Kind.EFFECTS -> { box.w = widest + 11; box.h = lines.size * 13 - 1 }
            else -> { box.w = widest + 26; box.h = lines.size * 18 + 2 }
        }
        val pos = position(box.kind)
        box.x = 4 + (pos.x * (width - 8 - box.w)).roundToInt()
        box.y = 4 + (pos.y * (height - 8 - box.h)).roundToInt()
    }

    fun draw(context: DrawContext, box: Box) {
        val font = MinecraftClient.getInstance().textRenderer
        val x = box.x
        var y = box.y
        when (box.kind) {
            Kind.INFO -> {
                context.roundRect(x, y, x + box.w, y + box.h, CHIP_BG)
                context.drawTextWithShadow(font, box.lines[0].text, x + 4, y + 3, TEXT)
            }
            Kind.EFFECTS -> for (line in box.lines) {
                context.roundRect(x, y, x + line.width + 11, y + 12, CHIP_BG)
                context.fill(x, y + 1, x + 2, y + 11, line.color)
                context.drawTextWithShadow(font, line.text, x + 6, y + 2, TEXT)
                y += 13
            }
            else -> {
                context.roundRect(x, y, x + box.w, y + box.h, CHIP_BG)
                y += 1
                for (line in box.lines) {
                    context.drawItem(line.icon, x + 2, y)
                    context.drawTextWithShadow(font, line.text, x + box.w - 4 - line.width, y + 4, line.color)
                    y += 18
                }
            }
        }
    }

    private fun renderHud(context: DrawContext, counter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        if (client.options.hudHidden || client.currentScreen is HudEditScreen) return
        val debug = client.debugHud.shouldShowDebugHud()
        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight

        if (s.lowHealth && player.isAlive && player.health <= LOW_HEALTH && !player.isCreative && !player.isSpectator) {
            val pulse = (sin(Util.getMeasuringTimeMs() / 160.0) + 1.0) / 2.0
            val a = (0x30 + 0x40 * pulse).toInt()
            context.fillGradient(0, 0, w, 28, alpha(WARN, a), alpha(WARN, 0))
            context.fillGradient(0, h - 28, w, h, alpha(WARN, 0), alpha(WARN, a))
            for (i in 0 until 6) {
                val step = alpha(WARN, a * (6 - i) / 6)
                context.fill(i * 4, 0, i * 4 + 4, h, step)
                context.fill(w - i * 4 - 4, 0, w - i * 4, h, step)
            }
        }

        for (box in boxes) {
            val lines = when (box.kind) {
                Kind.INFO -> if (debug) emptyList() else infoLines
                Kind.EFFECTS -> if (debug) emptyList() else effectLines
                Kind.ARMOR -> armorLines
                Kind.TRACKER -> trackerLines
            }
            if (lines.isEmpty()) continue
            layout(box, lines, w, h)
            draw(context, box)
        }
    }

    fun zoom(client: MinecraftClient, held: Boolean) {
        val options = client.options
        val want = held && s.zoom && client.player != null && client.currentScreen == null
        if (want && !zooming) {
            zoomFov = options.fov.value
            zoomSmooth = options.smoothCameraEnabled
            options.fov.value = ZOOM_FOV
            options.smoothCameraEnabled = true
            zooming = true
        } else if (!want && zooming) {
            options.fov.value = zoomFov
            options.smoothCameraEnabled = zoomSmooth
            zooming = false
        }
    }

    private fun selected(handler: MerchantScreenHandler): Int {
        val output = handler.getSlot(2).stack
        if (output.isEmpty) return -1
        return handler.recipes.indexOfFirst {
            !it.isDisabled && ItemStack.areItemsAndComponentsEqual(it.sellItem, output) &&
                ItemStack.areItemsEqual(it.displayedFirstBuyItem, handler.getSlot(0).stack)
        }
    }

    private fun refreshTrade(player: ClientPlayerEntity) {
        tradeLabel = TRADE_LABEL
        val handler = player.currentScreenHandler as? MerchantScreenHandler ?: return
        val index = selected(handler)
        if (index < 0) return
        val offer = handler.recipes[index]
        fun affordable(price: ItemStack): Int {
            if (price.isEmpty) return Int.MAX_VALUE
            var have = 0
            for (i in handler.slots.indices) {
                if (i == 2) continue
                val stack = handler.getSlot(i).stack
                if (ItemStack.areItemsEqual(stack, price)) have += stack.count
            }
            return have / price.count
        }
        val rounds = minOf(affordable(offer.displayedFirstBuyItem), affordable(offer.displayedSecondBuyItem),
            offer.maxUses - offer.uses)
        if (rounds > 0) tradeLabel = "Trade ×$rounds"
    }

    private fun decorate(screen: Screen, width: Int, height: Int) {
        if (screen !is HandledScreen<*>) return
        val handler = screen.screenHandler
        val buttons = Screens.getButtons(screen)
        when {
            handler is MerchantScreenHandler && s.tradeMax -> {
                val right = (width + 276) / 2
                val top = (height - 166) / 2
                buttons.add(Ui.Button(right - 64, top - 17, 64, 14, TRADE_LABEL, true, { tradeLabel }) { startTrades(handler) })
            }
            s.containerButtons && (handler is GenericContainerScreenHandler || handler is ShulkerBoxScreenHandler) -> {
                val rows = (handler.slots.size - PLAYER_SLOTS) / 9
                val right = (width + 176) / 2
                val top = (height - (114 + rows * 18)) / 2
                buttons.add(Ui.Button(right - 132, top - 17, 48, 14, "Take all", true) { queue(handler, Move.TAKE) })
                buttons.add(Ui.Button(right - 82, top - 17, 44, 14, "Deposit", false) { queue(handler, Move.DEPOSIT) })
                buttons.add(Ui.Button(right - 36, top - 17, 36, 14, "Stack", false) { queue(handler, Move.STACK) })
            }
        }
    }

    private enum class Move { TAKE, DEPOSIT, STACK }

    private fun queue(handler: ScreenHandler, move: Move) {
        val size = handler.slots.size - PLAYER_SLOTS
        if (size <= 0) return
        clicks.clear()
        clickSync = handler.syncId
        when (move) {
            Move.TAKE -> for (i in 0 until size) if (handler.getSlot(i).hasStack()) clicks += i
            Move.DEPOSIT -> for (i in size until size + MAIN_SLOTS) if (handler.getSlot(i).hasStack()) clicks += i
            Move.STACK -> {
                val stored = HashSet<Item>()
                for (i in 0 until size) handler.getSlot(i).stack.let { if (!it.isEmpty) stored += it.item }
                for (i in size until size + MAIN_SLOTS) {
                    val stack = handler.getSlot(i).stack
                    if (!stack.isEmpty && stack.item in stored) clicks += i
                }
            }
        }
    }

    private fun runClicks(client: MinecraftClient, player: ClientPlayerEntity) {
        if (clicks.isEmpty()) return
        val handler = player.currentScreenHandler
        val manager = client.interactionManager
        if (manager == null || handler.syncId != clickSync) {
            clicks.clear()
            return
        }
        repeat(minOf(CLICKS_PER_TICK, clicks.size)) {
            val slot = clicks.removeFirst()
            if (slot < handler.slots.size && handler.getSlot(slot).hasStack()) {
                manager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player)
            }
        }
    }

    private fun startTrades(handler: MerchantScreenHandler) {
        val player = MinecraftClient.getInstance().player ?: return
        if (!handler.getSlot(2).hasStack()) {
            SlotCyclerMod.announce(player, "Select a trade you can afford first" to SlotCyclerMod.BAD)
            return
        }
        tradesLeft = MAX_TRADES
        tradesDone = 0
        tradeWait = 0
    }

    private fun runTrades(client: MinecraftClient, player: ClientPlayerEntity) {
        if (tradesLeft <= 0) return
        val handler = player.currentScreenHandler as? MerchantScreenHandler
        val manager = client.interactionManager
        if (handler == null || manager == null) {
            tradesLeft = 0
            return
        }
        if (tradeWait > 0) {
            tradeWait--
            return
        }
        val index = selected(handler)
        if (index < 0) {
            tradesLeft = 0
            SlotCyclerMod.announce(player, "Trading finished" to SlotCyclerMod.GOOD,
                "$tradesDone rounds" to SlotCyclerMod.SOFT)
            return
        }
        manager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player)
        handler.setRecipeIndex(index)
        handler.switchTo(index)
        player.networkHandler.sendPacket(SelectMerchantTradeC2SPacket(index))
        tradesDone++
        tradesLeft--
        tradeWait = TRADE_WAIT
        if (tradesLeft == 0) {
            SlotCyclerMod.announce(player, "Trading finished" to SlotCyclerMod.GOOD,
                "$tradesDone rounds" to SlotCyclerMod.SOFT)
        }
    }
}
