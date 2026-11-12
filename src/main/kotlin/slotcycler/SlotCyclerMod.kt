package slotcycler

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.network.ClientPlayerInteractionManager
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.screen.sync.ItemStackHash
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Slot Cycler.
 *
 * Press the run key to move the stack in hotbar slot A to slot B and back.
 * Afterwards the mod can ask the server for the real inventory to count ghost
 * items (items the client shows but the server does not have), and drop
 * anything slot A holds beyond what it started with.
 *
 * Everything is driven by [Settings] and runs on the client thread as a small
 * tick state machine.
 */
class SlotCyclerMod : ClientModInitializer {

    companion object {
        const val MOD_ID = "slotcycler"
        private const val MOUSE_BUTTON_LEFT = 0
        /** Screen-handler slot id of hotbar slot 1. */
        private const val HOTBAR_START = 36
        private const val MAX_DROPS_PER_TICK = 64
        /** A click on this slot id is accepted by the server and does nothing. */
        private const val NO_OP_SLOT: Short = -1

        /** Set by the settings screen's "Run" button; consumed next tick. */
        var runRequested = false
        /** Set by the settings screen's "Clear ghosts" button; consumed next tick. */
        var clearRequested = false
    }

    private enum class Phase { IDLE, CLICKING, SETTLING, VERIFYING }

    private val category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))
    private val cycleKey =
        KeyBinding("key.$MOD_ID.cycle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, category)
    private val configKey =
        KeyBinding("key.$MOD_ID.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category)

    private var phase = Phase.IDLE
    /** Settings captured when the run started, so edits mid-run can't break it. */
    private var s = Settings.current
    private var sequence = IntArray(4)
    private var totalSteps = 0
    private var step = 0
    private var wait = 0
    private var startCount = 0
    private var dropped = 0
    private var ghosts = 0
    /** The client's view of every slot (cursor last) taken just before a ghost check. */
    private var snapshot: List<ItemStack> = emptyList()
    /** The ghost copy being kept on screen: inventory index and what to show there. */
    private var keptGhost: Pair<Int, ItemStack>? = null

    override fun onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(cycleKey)
        KeyBindingHelper.registerKeyBinding(configKey)
        ClientTickEvents.END_CLIENT_TICK.register(::onTick)
    }

    private fun onTick(client: MinecraftClient) {
        // Always drain presses so ones made mid-run don't queue up a re-run.
        var pressed = runRequested
        runRequested = false
        while (cycleKey.wasPressed()) pressed = true
        var openConfig = false
        while (configKey.wasPressed()) openConfig = true

        if (phase == Phase.IDLE) {
            maintainGhost(client.player)
            if (openConfig && client.currentScreen == null) client.setScreen(ConfigScreen())
            else if (pressed) start(client)
            return
        }

        val player = client.player
        val manager = client.interactionManager
        // Left the world or opened a container: stop rather than click the wrong slots.
        if (player == null || manager == null ||
            player.currentScreenHandler !== player.playerScreenHandler
        ) {
            phase = Phase.IDLE
            player?.sendMessage(Text.literal("[SlotCycler] Cancelled"), true)
            return
        }

        when (phase) {
            Phase.CLICKING -> clickTick(player, manager)
            Phase.SETTLING -> {
                if (dropsAsItGoes()) dropSurplus(player, manager)
                if (wait-- <= 0) {
                    if (s.ghostCheck) beginGhostCheck(player) else finish(player, manager)
                }
            }
            Phase.VERIFYING -> if (wait-- <= 0) {
                ghosts = countGhosts(player)
                finish(player, manager)
            }
            Phase.IDLE -> {}
        }
    }

    /**
     * While idle, keep the ghost copy on screen: the server empties its slot
     * whenever it resyncs (for example after the ghost is clicked), so put it
     * back. Stop once a real item takes the slot or the player leaves.
     */
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
            // Only the ghost itself is removed; a real item in the slot is left alone.
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
            player.sendMessage(Text.literal("[SlotCycler] Slot A and slot B are the same"), true)
            return
        }
        val count = watchedCount(player)
        if (count == 0) {
            player.sendMessage(Text.literal("[SlotCycler] Slot ${s.slotA} is empty"), true)
            return
        }

        val a = HOTBAR_START + s.slotA - 1
        val b = HOTBAR_START + s.slotB - 1
        // Pick up A, place in B, pick up B, place back in A.
        sequence = intArrayOf(a, b, b, a)
        totalSteps = s.totalClicks()
        step = 0
        wait = 0
        startCount = count
        dropped = 0
        ghosts = 0
        keptGhost = null
        phase = Phase.CLICKING
    }

    private fun clickTick(player: ClientPlayerEntity, manager: ClientPlayerInteractionManager) {
        if (wait > 0) {
            wait--
            return
        }
        // The stack is back in slot A at every cycle boundary; check it there.
        if (step % sequence.size == 0 && dropsAsItGoes()) dropSurplus(player, manager)

        repeat(minOf(s.clicksPerTick, totalSteps - step)) {
            val slot = sequence[step % sequence.size]
            if (s.ghostClicks) {
                // Client only: the server is never told, so the result is a ghost.
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
            if (s.ghostClicks) addGhostCopy(player)
            phase = Phase.SETTLING
            wait = s.settleTicks
        } else {
            wait = s.burstDelay
        }
    }

    /**
     * Ghost runs end with the item showing in both slots: whichever of A and B
     * is empty gets a single client-only copy of the other's item.
     */
    private fun addGhostCopy(player: ClientPlayerEntity) {
        val inventory = player.inventory
        val a = s.slotA - 1
        val b = s.slotB - 1
        val (from, to) = if (inventory.getStack(a).isEmpty) b to a else a to b
        val source = inventory.getStack(from)
        if (source.isEmpty) return
        val copy = source.copyWithCount(1)
        inventory.setStack(to, copy)
        keptGhost = if (s.keepGhost) to to copy.copy() else null
    }

    /**
     * Surplus is dropped during the run only when the client's counts can be
     * trusted as they are. With ghost clicks or a pending ghost check, wait
     * until the end instead.
     */
    private fun dropsAsItGoes() = s.dropSurplus && !s.ghostClicks && !s.ghostCheck

    private fun dropSurplus(player: ClientPlayerEntity, manager: ClientPlayerInteractionManager) {
        val surplus = (watchedCount(player) - startCount).coerceAtMost(MAX_DROPS_PER_TICK)
        repeat(surplus) {
            // THROW with button 0 drops one item from the slot, like pressing Q.
            manager.clickSlot(
                player.playerScreenHandler.syncId, HOTBAR_START + s.slotA - 1,
                MOUSE_BUTTON_LEFT, SlotActionType.THROW, player
            )
        }
        if (surplus > 0) dropped += surplus
    }

    /**
     * Remember what the client believes, then ask the server for the truth.
     * [requestResync] sends a do-nothing click carrying a deliberately wrong
     * revision number; the server answers a revision mismatch by resending the
     * whole inventory, which overwrites any ghosts.
     */
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

    /** Items the client had in [snapshot] that the server's inventory does not. */
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
        // The ghost check's resync wiped the ghost copy; put it back so it stays.
        if (s.ghostClicks && s.ghostCheck) addGhostCopy(player)
        phase = Phase.IDLE

        val parts = mutableListOf<String>()
        if (s.ghostCheck) parts += if (ghosts > 0) "$ghosts ghost item(s) found" else "no ghost items"
        if (s.dropSurplus) parts += if (dropped > 0) "dropped $dropped extra" else "no extra items"
        val summary = if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
        player.sendMessage(Text.literal("[SlotCycler] Done$summary"), true)
    }

    private fun watchedCount(player: ClientPlayerEntity): Int =
        player.inventory.getStack(s.slotA - 1).count
}
