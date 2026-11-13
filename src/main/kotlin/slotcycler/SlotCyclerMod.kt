package slotcycler

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.network.ClientPlayerInteractionManager
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.screen.sync.ItemStackHash
import net.minecraft.registry.Registries
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class SlotCyclerMod : ClientModInitializer {

    companion object {
        const val MOD_ID = "slotcycler"
        val VERSION: String by lazy {
            FabricLoader.getInstance().getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("dev")
        }
        private const val MOUSE_BUTTON_LEFT = 0
        private const val HOTBAR_START = 36
        private const val MAX_DROPS_PER_TICK = 64
        private const val NO_OP_SLOT: Short = -1

        var runRequested = false
        var clearRequested = false

        const val CYAN = 0x35E0FF
        private const val VIOLET = 0xB65CFF
        const val GOOD = 0x5CFFA0
        const val BAD = 0xFF6B7A
        const val SOFT = 0xE8ECFF
        const val DIM = 0x8A90B0

        private fun mix(t: Float, a: Int, b: Int): Int {
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t).toInt().coerceIn(0, 255) shl shift
            }
            return ch(16) or ch(8) or ch(0)
        }

        private fun gradient(text: String, from: Int, to: Int): MutableText {
            val out = Text.empty()
            text.forEachIndexed { i, c ->
                val t = if (text.length > 1) i.toFloat() / (text.length - 1) else 0f
                out.append(Text.literal(c.toString()).styled { it.withColor(mix(t, from, to)).withBold(true) })
            }
            return out
        }

        private fun tint(text: String, color: Int): MutableText =
            Text.literal(text).styled { it.withColor(color) }

        fun announce(player: ClientPlayerEntity, vararg parts: Pair<String, Int>) {
            val line = Text.empty()
                .append(gradient("Slot Cycler", CYAN, VIOLET))
                .append(Text.literal(" "))
            parts.forEachIndexed { i, (text, color) ->
                line.append(tint(if (i == 0) "\u00BB " else " \u00B7 ", DIM))
                line.append(tint(text, color))
            }
            player.sendMessage(line, true)
        }
    }

    private enum class Phase { IDLE, CLICKING, SETTLING, VERIFYING }

    private val category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))
    private val cycleKey =
        KeyBinding("key.$MOD_ID.cycle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, category)
    private val configKey =
        KeyBinding("key.$MOD_ID.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category)

    private var phase = Phase.IDLE
    private var s = Settings.current
    private var sequence = IntArray(4)
    private var totalSteps = 0
    private var step = 0
    private var wait = 0
    private var startCount = 0
    private var startStack: ItemStack = ItemStack.EMPTY
    private var targetWasEmpty = false
    private var dropped = 0
    private var ghosts = 0
    private var realCopy = false
    private var snapshot: List<ItemStack> = emptyList()
    private var keptGhost: Pair<Int, ItemStack>? = null

    override fun onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(cycleKey)
        KeyBindingHelper.registerKeyBinding(configKey)
        ClientTickEvents.END_CLIENT_TICK.register(::onTick)
        Qol.init()
    }

    private fun onTick(client: MinecraftClient) {
        var pressed = runRequested
        runRequested = false
        while (cycleKey.wasPressed()) pressed = true
        var openConfig = false
        while (configKey.wasPressed()) openConfig = true

        if (phase == Phase.IDLE) {
            maintainGhost(client.player)
            if (openConfig && client.currentScreen == null) client.setScreen(HubScreen())
            else if (pressed) start(client)
            return
        }

        val player = client.player
        val manager = client.interactionManager
        if (player == null || manager == null ||
            player.currentScreenHandler !== player.playerScreenHandler
        ) {
            phase = Phase.IDLE
            player?.let { announce(it, "Cancelled" to BAD) }
            return
        }

        when (phase) {
            Phase.CLICKING -> clickTick(client, player, manager)
            Phase.SETTLING -> {
                if (dropsAsItGoes()) dropSurplus(player, manager)
                if (wait-- <= 0) {
                    if (s.ghostCheck) beginGhostCheck(player) else finish(player, manager)
                }
            }
            Phase.VERIFYING -> if (wait-- <= 0) {
                ghosts = countGhosts(player)
                if (s.ghostClicks && realCopy) realCopy = hasRealCopy(player)
                finish(player, manager)
            }
            Phase.IDLE -> {}
        }
    }

    private fun maintainGhost(player: ClientPlayerEntity?) {
        val clear = clearRequested
        clearRequested = false
        val (index, stack) = keptGhost ?: return
        if (player == null) {
            keptGhost = null
            return
        }
        if (clear || !Settings.current.keepGhost) {
            keptGhost = null
            if (player.currentScreenHandler === player.playerScreenHandler) requestResync(player)
            return
        }
        val shown = player.inventory.getStack(index)
        when {
            shown.isEmpty -> player.inventory.setStack(index, stack.copy())
            !ItemStack.areItemsAndComponentsEqual(shown, stack) -> keptGhost = null
        }
    }

    private fun start(client: MinecraftClient) {
        val player = client.player ?: return
        if (player.currentScreenHandler !== player.playerScreenHandler) return

        s = Settings.current
        if (s.slotA == s.slotB) {
            announce(player, "Slot A and slot B are the same" to BAD)
            return
        }
        val count = watchedCount(player)
        if (count == 0) {
            announce(player, "Slot ${s.slotA} is empty" to BAD)
            return
        }
        val itemMode = s.itemMode ?: Settings.ANY_ITEM
        val heldId = Registries.ITEM.getId(player.inventory.getStack(s.slotA - 1).item).path
        if (itemMode != Settings.ANY_ITEM && heldId != itemMode) {
            announce(player, "Slot ${s.slotA} must hold $itemMode" to BAD, "found $heldId" to DIM)
            return
        }

        val a = HOTBAR_START + s.slotA - 1
        val b = HOTBAR_START + s.slotB - 1
        sequence = intArrayOf(a, b, b, a)
        totalSteps = s.totalClicks()
        step = 0
        wait = 0
        startCount = count
        startStack = player.inventory.getStack(s.slotA - 1).copy()
        targetWasEmpty = player.inventory.getStack(s.slotB - 1).isEmpty
        dropped = 0
        ghosts = 0
        keptGhost = null
        realCopy = false
        phase = Phase.CLICKING
        announce(
            player,
            (if (s.ghostClicks) "Ghost run" else "Real run") to (if (s.ghostClicks) VIOLET else GOOD),
            "$totalSteps clicks" to SOFT,
            heldId to DIM
        )
    }

    private fun clickTick(
        client: MinecraftClient,
        player: ClientPlayerEntity,
        manager: ClientPlayerInteractionManager
    ) {
        if (wait > 0) {
            wait--
            return
        }
        if (step % sequence.size == 0 && dropsAsItGoes()) dropSurplus(player, manager)

        repeat(minOf(s.clicksPerTick, totalSteps - step)) {
            val slot = sequence[step % sequence.size]
            if (s.ghostClicks) {
                player.playerScreenHandler
                    .onSlotClick(slot, MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player)
            } else {
                manager.clickSlot(
                    player.playerScreenHandler.syncId, slot,
                    MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player
                )
            }
            step++
        }

        if (step >= totalSteps) {
            if (s.ghostClicks) {
                realCopy = makeRealCopy(client, player)
                if (!realCopy) addGhostCopy(player)
            }
            phase = Phase.SETTLING
            wait = s.settleTicks
        } else {
            wait = s.burstDelay
        }
    }

    private fun makeRealCopy(client: MinecraftClient, player: ClientPlayerEntity): Boolean {
        if (!s.realCopy || !targetWasEmpty || startStack.isEmpty) return false

        val a = s.slotA - 1
        val b = s.slotB - 1
        val expected = startStack.copy()
        val copy = expected.copyWithCount(1)
        val server = client.server
        if (server != null) {
            val playerId = player.uuid
            server.execute {
                val serverPlayer = server.playerManager.getPlayer(playerId) ?: return@execute
                val inventory = serverPlayer.inventory
                val source = inventory.getStack(a)
                if (source.count == expected.count &&
                    ItemStack.areItemsAndComponentsEqual(source, expected) &&
                    inventory.getStack(b).isEmpty
                ) {
                    inventory.setStack(b, copy)
                    serverPlayer.playerScreenHandler.syncState()
                }
            }
            return true
        }

        if (!player.abilities.creativeMode) return false
        player.networkHandler.sendPacket(CreativeInventoryActionC2SPacket(HOTBAR_START + b, copy))
        return true
    }

    private fun hasRealCopy(player: ClientPlayerEntity): Boolean {
        val inventory = player.inventory
        val source = inventory.getStack(s.slotA - 1)
        val copy = inventory.getStack(s.slotB - 1)
        return source.count == startStack.count &&
            ItemStack.areItemsAndComponentsEqual(source, startStack) &&
            copy.count == 1 && ItemStack.areItemsAndComponentsEqual(copy, startStack)
    }

    private fun addGhostCopy(player: ClientPlayerEntity) {
        val inventory = player.inventory
        val a = s.slotA - 1
        val b = s.slotB - 1
        if (!inventory.getStack(a).isEmpty && !inventory.getStack(b).isEmpty) {
            keptGhost = null
            return
        }
        val (from, to) = if (inventory.getStack(a).isEmpty) b to a else a to b
        val source = inventory.getStack(from)
        if (source.isEmpty) return
        val copy = source.copyWithCount(1)
        inventory.setStack(to, copy)
        keptGhost = if (s.keepGhost) to to copy.copy() else null
    }

    private fun dropsAsItGoes() = s.dropSurplus && !s.ghostClicks && !s.ghostCheck

    private fun dropSurplus(player: ClientPlayerEntity, manager: ClientPlayerInteractionManager) {
        val surplus = (watchedCount(player) - startCount).coerceAtMost(MAX_DROPS_PER_TICK)
        repeat(surplus) {
            manager.clickSlot(
                player.playerScreenHandler.syncId, HOTBAR_START + s.slotA - 1,
                MOUSE_BUTTON_LEFT, SlotActionType.THROW, player
            )
        }
        if (surplus > 0) dropped += surplus
    }

    private fun beginGhostCheck(player: ClientPlayerEntity) {
        val handler = player.playerScreenHandler
        snapshot = handler.slots.map { it.stack.copy() } + handler.cursorStack.copy()
        requestResync(player)
        phase = Phase.VERIFYING
        wait = s.verifyTicks
    }

    private fun requestResync(player: ClientPlayerEntity) {
        val handler = player.playerScreenHandler
        player.networkHandler.sendPacket(
            ClickSlotC2SPacket(
                handler.syncId, (handler.revision + 1) and 0x7FFF, NO_OP_SLOT,
                MOUSE_BUTTON_LEFT.toByte(), SlotActionType.PICKUP,
                Int2ObjectMaps.emptyMap(), ItemStackHash.EMPTY
            )
        )
    }

    private fun countGhosts(player: ClientPlayerEntity): Int {
        val handler = player.playerScreenHandler
        val now = handler.slots.map { it.stack } + handler.cursorStack
        var count = 0
        for (i in snapshot.indices) {
            val before = snapshot[i]
            val after = now.getOrNull(i) ?: continue
            if (before.isEmpty) continue
            count += when {
                !ItemStack.areItemsAndComponentsEqual(before, after) -> before.count
                after.count < before.count -> before.count - after.count
                else -> 0
            }
        }
        snapshot = emptyList()
        return count
    }

    private fun finish(player: ClientPlayerEntity, manager: ClientPlayerInteractionManager) {
        if (s.dropSurplus) dropSurplus(player, manager)
        if (s.ghostClicks && s.ghostCheck && !realCopy) addGhostCopy(player)
        phase = Phase.IDLE

        val parts = mutableListOf<Pair<String, Int>>("Done" to GOOD)
        if (s.ghostCheck) {
            parts += if (ghosts > 0) "$ghosts ghost item(s) found" to BAD else "no ghost items" to SOFT
        }
        if (s.ghostClicks && s.realCopy) {
            parts += when {
                realCopy && s.ghostCheck -> "real copy confirmed" to GOOD
                realCopy -> "real copy requested (not verified)" to SOFT
                else -> "real copy not confirmed, using display copy" to VIOLET
            }
        }
        if (s.dropSurplus) {
            parts += when {
                dropped > 0 -> "dropped $dropped extra" to CYAN
                !s.ghostClicks && s.ghostCheck -> "server confirmed no extra items" to SOFT
                else -> "no extra items" to SOFT
            }
        }
        announce(player, *parts.toTypedArray())
    }

    private fun watchedCount(player: ClientPlayerEntity): Int =
        player.inventory.getStack(s.slotA - 1).count
}
