package slotcycler

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Slot Cycler.
 *
 * Press 'X' to move the item in player-inventory slot 36 (hotbar slot 1)
 * through slot 37 (hotbar slot 2) and back, repeating [ATTEMPTS] times.
 *
 *  - Uses a Fabric [KeyBinding] registered once via [KeyBindingHelper]: no raw
 *    GLFW window-handle lookups and no key binding re-created every tick.
 *  - [KeyBinding.wasPressed] debounces input, so holding the key does not
 *    restart the sequence every tick.
 *  - The click sequence runs on the main client thread as a tick state machine,
 *    so [net.minecraft.client.network.ClientPlayerInteractionManager.clickSlot]
 *    is never invoked from a background thread (thread-safe, no game freeze).
 */
class SlotCyclerMod : ClientModInitializer {

    companion object {
        /** Mod id, used for the keybinding category namespace. */
        private const val MOD_ID = "slotcycler"
        /** GLFW key code for the 'X' key. */
        private const val TRIGGER_KEY = GLFW.GLFW_KEY_X
        /** Sync id of the player's own inventory container. */
        private const val PLAYER_INVENTORY_SYNC_ID = 0
        /** Mouse button used for clicks (0 = left click). */
        private const val MOUSE_BUTTON_LEFT = 0
        /** Ticks to wait between each click step. */
        private const val STEP_DELAY_TICKS = 2
        /** How many times the four-step slot sequence repeats. */
        private const val ATTEMPTS = 3
        /**
         * Slot order: pick up slot 36, place in 37, pick up 37, place back in 36.
         * Every step is a left click ([SlotActionType.PICKUP]): picking up an
         * empty slot grabs the stack, clicking a slot while holding a stack
         * places it.
         */
        private val SLOT_SEQUENCE = intArrayOf(36, 37, 37, 36)
    }

    /** Custom keybinding category (1.21.11 requires a [KeyBinding.Category]). */
    private val category: KeyBinding.Category =
        KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))

    /** Key that starts the cycle sequence. */
    private val cycleKey: KeyBinding = KeyBinding(
        "key.$MOD_ID.cycle",
        InputUtil.Type.KEYSYM,
        TRIGGER_KEY,
        category
    )

    /** Whether a sequence is currently running. */
    private var active = false
    /** Index of the next click step to execute. */
    private var step = 0
    /** Ticks remaining before the next click step. */
    private var delay = 0

    override fun onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(cycleKey)

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Start a new sequence when the key is pressed (debounced by wasPressed()).
            if (!active && cycleKey.wasPressed()) {
                startSequence(client)
            }
            if (active) {
                advanceSequence(client)
            }
        }
    }

    private fun startSequence(client: MinecraftClient) {
        val player: PlayerEntity = client.player ?: return

        active = true
        step = 0
        delay = 0
        player.sendMessage(Text.literal("[SlotCycler] Sequence started ($ATTEMPTS x)"), true)
    }

    private fun advanceSequence(client: MinecraftClient) {
        if (delay > 0) {
            delay--
            return
        }

        val player: PlayerEntity = client.player ?: return
        val manager = client.interactionManager ?: return

        val totalSteps = SLOT_SEQUENCE.size * ATTEMPTS
        if (step >= totalSteps) {
            finishSequence(client)
            return
        }

        val slot = SLOT_SEQUENCE[step % SLOT_SEQUENCE.size]
        manager.clickSlot(PLAYER_INVENTORY_SYNC_ID, slot, MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player)

        step++
        delay = STEP_DELAY_TICKS
    }

    private fun finishSequence(client: MinecraftClient) {
        active = false
        step = 0
        delay = 0
        client.player?.sendMessage(Text.literal("[SlotCycler] Sequence complete!"), true)
    }
}
