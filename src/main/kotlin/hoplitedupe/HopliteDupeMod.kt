package hoplitedupe

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Hand
import java.util.concurrent.TimeUnit

class HopliteDupeMod : ClientModInitializer {
    override fun onInitializeClient() {
        // Trigger on Key 'X'
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val key = net.minecraft.client.util.InputUtil.Type.KEYSYM.bind(GLFW.GLFW_KEY_X)
            val window = client.gameWindow

            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_X) == GLFW.GLFW_PRESS) {
                val player = client.player ?: return@register
                val interactionManager = player.interactionManager

                // Start the dupe sequence in a separate thread to avoid game freezing
                Thread {
                    println("[Dupe] Sequence Started...")
                    
                    (1..3).forEach { attempt ->
                        // 1. Pick Up Slot 1 (Index 36)
                        interactionManager?.clickSlot(0, 36, 0, 1, player)
                        Thread.sleep(15) // Artificial Lag

                        // 2. Put into Slot 2 (Index 37)
                        interactionManager?.clickSlot(0, 37, 0, 1, player)
                        Thread.sleep(15) // Artificial Lag

                        // 3. Pick Up Slot 2 (Back to Cursor)
                        interactionManager?.clickSlot(0, 37, 0, 1, player)
                        Thread.sleep(15) // Artificial Lag

                        // 4. Put back in Slot 1 (Restore)
                        interactionManager?.clickSlot(0, 36, 0, 1, player)
                        Thread.sleep(15) // Artificial Lag
                    }
                    println("[Dupe] Sequence Complete!")
                }.start()

                // Debounce: Wait 0.5 seconds before pressing next (Optional but good practice)
                // ...implementation of debounce omitted for simplicity...
            }
        }
    }
}
