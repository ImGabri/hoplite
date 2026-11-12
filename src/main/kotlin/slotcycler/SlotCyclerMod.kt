package slotcycler

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Slot Cycler.
 *
 * Press 'X' to move the item in hotbar slot 1 to hotbar slot 2 and back,
 * [CYCLES] times. If slot 1 ever holds more items than it did when the
 * sequence started, the surplus is dropped (the same thing pressing Q does).
 *
 * Everything runs on the client thread as a small tick state machine.
 */
class SlotCyclerMod : ClientModInitializer {

    private companion object {
        const val MOD_ID = "slotcycler"
        const val TRIGGER_KEY = GLFW.GLFW_KEY_X
        const val MOUSE_BUTTON_LEFT = 0

        /** Screen-handler slot ids of hotbar slots 1 and 2. */
        const val SLOT_A = 36
        const val SLOT_B = 37
        /** Inventory index of hotbar slot 1 (the slot watched for extra items). */
        const val WATCHED_HOTBAR_INDEX = 0

        /**
         * Clicks sent per tick. 2 = one complete switch (pick up + place) every
         * tick, so the cursor never holds the stack across ticks. Use 4 for a
         * full there-and-back per tick, or 1 for a slower, gentler pace.
         */
        const val CLICKS_PER_TICK = 2
        /** How many there-and-back cycles one key press performs. */
        const val CYCLES = 3
        /** Ticks to keep watching slot 1 after the last click, for server updates. */
        const val SETTLE_TICKS = 5
        /** Safety cap on items dropped in a single tick. */
        const val MAX_DROPS_PER_TICK = 64

        /** Pick up A, place in B, pick up B, place back in A. */
        val SLOT_SEQUENCE = intArrayOf(SLOT_A, SLOT_B, SLOT_B, SLOT_A)
        val TOTAL_STEPS = SLOT_SEQUENCE.size * CYCLES
    }

    private val cycleKey = KeyBinding(
        "key.$MOD_ID.cycle",
        InputUtil.Type.KEYSYM,
        TRIGGER_KEY,
        KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))
    )

    private var active = false
    /** Index of the next click to send. */
    private var step = 0
    /** Ticks left in the post-sequence watch window. */
    private var settle = 0
    /** Item count in slot 1 when the sequence started. */
    private var startCount = 0
    /** Surplus items dropped during this sequence. */
    private var dropped = 0

    override fun onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(cycleKey)
        ClientTickEvents.END_CLIENT_TICK.register(::onTick)
    }

    private fun onTick(client: MinecraftClient) {
        // Always drain presses so ones made mid-sequence don't queue up a re-run.
        var pressed = false
        while (cycleKey.wasPressed()) pressed = true

        if (!active) {
            if (pressed) start(client)
            return
        }

        val player = client.player
        val manager = client.interactionManager
        // Left the world or opened a container: stop rather than click the wrong slots.
        if (player == null || manager == null ||
            player.currentScreenHandler !== player.playerScreenHandler
        ) {
            active = false
            player?.sendMessage(Text.literal("[SlotCycler] Cancelled"), true)
            return
        }
        val syncId = player.playerScreenHandler.syncId

        // The stack is back in slot 1 at every cycle boundary; check it there.
        if (step % SLOT_SEQUENCE.size == 0) {
            val surplus = (watchedCount(player) - startCount).coerceAtMost(MAX_DROPS_PER_TICK)
            repeat(surplus) {
                // THROW with button 0 drops one item from the slot, like pressing Q.
                manager.clickSlot(syncId, SLOT_A, MOUSE_BUTTON_LEFT, SlotActionType.THROW, player)
            }
            if (surplus > 0) dropped += surplus
        }

        if (step >= TOTAL_STEPS) {
            if (settle-- <= 0) finish(player)
            return
        }

        repeat(minOf(CLICKS_PER_TICK, TOTAL_STEPS - step)) {
            manager.clickSlot(
                syncId, SLOT_SEQUENCE[step % SLOT_SEQUENCE.size],
                MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player
            )
            step++
        }
    }

    private fun start(client: MinecraftClient) {
        val player = client.player ?: return
        if (player.currentScreenHandler !== player.playerScreenHandler) return

        val count = watchedCount(player)
        if (count == 0) {
            player.sendMessage(Text.literal("[SlotCycler] Slot 1 is empty"), true)
            return
        }
        active = true
        step = 0
        settle = SETTLE_TICKS
        startCount = count
        dropped = 0
    }

    private fun finish(player: ClientPlayerEntity) {
        active = false
        val result = if (dropped > 0) "dropped $dropped extra" else "no extra items"
        player.sendMessage(Text.literal("[SlotCycler] Done: $result"), true)
    }

    private fun watchedCount(player: ClientPlayerEntity): Int =
        player.inventory.getStack(WATCHED_HOTBAR_INDEX).count
}
