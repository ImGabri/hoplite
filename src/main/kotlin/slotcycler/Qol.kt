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
import slotcycler.Ui.AMBER
import slotcycler.Ui.CHIP_BG
import slotcycler.Ui.OK
import slotcycler.Ui.TEXT
import slotcycler.Ui.TEXT_DIM
import slotcycler.Ui.WARN
import slotcycler.Ui.roundRect

object Qol {
    private const val CLICKS_PER_TICK = 4
    private const val MAX_TRADES = 64
    private const val TRADE_WAIT = 3
    private const val PLAYER_SLOTS = 36
    private const val MAIN_SLOTS = 27
    private const val LOW = 0.10f
    private const val MID = 0.35f

    private class Line(val icon: ItemStack, val text: String, val color: Int, val width: Int)

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

    private var infoLine = ""
    private var infoWidth = 0
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
        if (age % 4 == 0) refreshHud(client, player)
        if (age % 20 == 0 && s.durabilityAlert) durabilityAlert(player)
        runClicks(client, player)
        runTrades(client, player)
    }

    private fun fullbright(player: ClientPlayerEntity) {
        val current = player.getStatusEffect(StatusEffects.NIGHT_VISION)
        val ours = current != null && current.isAmbient && !current.shouldShowIcon()
        if (s.fullbright) {
            if (current == null || (ours && current.duration < 300)) {
                player.addStatusEffect(StatusEffectInstance(StatusEffects.NIGHT_VISION, 600, 0, true, false, false))
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
            infoLine = "${pos.x} ${pos.y} ${pos.z}  ·  $facing  ·  $ping ms"
            infoWidth = font.getWidth(infoLine)
        }

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

    private fun renderHud(context: DrawContext, counter: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        if (client.player == null || client.options.hudHidden) return
        val font = client.textRenderer
        val debug = client.debugHud.shouldShowDebugHud()

        var y = 4
        if (s.infoHud && !debug && infoLine.isNotEmpty()) {
            context.roundRect(4, y, 4 + infoWidth + 8, y + 14, CHIP_BG)
            context.drawTextWithShadow(font, infoLine, 8, y + 3, TEXT)
            y += 17
        }
        if (!debug) {
            for (line in effectLines) {
                context.roundRect(4, y, 4 + line.width + 8, y + 12, CHIP_BG)
                context.drawTextWithShadow(font, line.text, 8, y + 2, line.color)
                y += 13
            }
        }

        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight
        column(context, armorLines, w, h - 4 - armorLines.size * 18)
        column(context, trackerLines, w, (h - trackerLines.size * 18) / 2)
    }

    private fun column(context: DrawContext, lines: List<Line>, screenWidth: Int, top: Int) {
        if (lines.isEmpty()) return
        val font = MinecraftClient.getInstance().textRenderer
        val widest = lines.maxOf { it.width }
        val x = screenWidth - 4 - widest - 24
        context.roundRect(x, top - 2, screenWidth - 4, top + lines.size * 18, CHIP_BG)
        var y = top
        for (line in lines) {
            context.drawItem(line.icon, x + 2, y)
            context.drawTextWithShadow(font, line.text, screenWidth - 8 - line.width, y + 4, line.color)
            y += 18
        }
    }

    private fun decorate(screen: Screen, width: Int, height: Int) {
        if (screen !is HandledScreen<*>) return
        val handler = screen.screenHandler
        val buttons = Screens.getButtons(screen)
        when {
            handler is MerchantScreenHandler && s.tradeMax -> {
                val right = (width + 276) / 2
                val top = (height - 166) / 2
                buttons.add(Ui.Button(right - 64, top - 17, 64, 14, "Trade max", true) { startTrades(handler) })
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
        val output = handler.getSlot(2).stack
        val index = if (output.isEmpty) -1 else handler.recipes.indexOfFirst {
            !it.isDisabled && ItemStack.areItemsAndComponentsEqual(it.sellItem, output) &&
                ItemStack.areItemsEqual(it.displayedFirstBuyItem, handler.getSlot(0).stack)
        }
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
