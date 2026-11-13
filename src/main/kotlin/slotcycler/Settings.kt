package slotcycler

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** User-tunable options, saved to `config/slotcycler.json`. */
class Settings {
    /** Hotbar slot (1-9) the stack starts in, and the one watched for surplus. */
    var slotA = 1
    /** Hotbar slot (1-9) the stack is moved to. */
    var slotB = 2
    /** There-and-back cycles per key press. */
    var cycles = 3
    /** Clicks sent each active tick (2 = one complete move). */
    var clicksPerTick = 2
    /** Idle ticks between click bursts. */
    var burstDelay = 0
    /** Ticks to wait after the last click before checking results. */
    var settleTicks = 5
    /** Ticks to wait for the server's answer during a ghost check. */
    var verifyTicks = 10
    /**
     * Apply clicks on the client only, so the server never sees them, and
     * finish with a single ghost copy so the item shows in both slots.
     */
    var ghostClicks = false
    /** Redraw the ghost copy whenever the server clears it, until told to stop. */
    var keepGhost = true
    /**
     * Ask the server to make the copy a real item. Needs this mod on the
     * server (or a single-player world); otherwise the copy stays a ghost.
     */
    var realCopy = true
    /** After the run, ask the server for the real inventory and count ghost items. */
    var ghostCheck = true
    /** Drop anything in slot A beyond what it started with. */
    var dropSurplus = true
    /** Do one extra move at the end so the stack finishes in slot B. */
    var endInB = false

    fun clamp() {
        slotA = slotA.coerceIn(1, 9)
        slotB = slotB.coerceIn(1, 9)
        cycles = cycles.coerceIn(1, MAX_CYCLES)
        clicksPerTick = clicksPerTick.coerceIn(1, MAX_CLICKS_PER_TICK)
        burstDelay = burstDelay.coerceIn(0, MAX_DELAY)
        settleTicks = settleTicks.coerceIn(0, MAX_WAIT)
        verifyTicks = verifyTicks.coerceIn(2, MAX_WAIT)
    }

    /** Clicks in one run. */
    fun totalClicks(): Int = cycles * 4 + if (endInB) 2 else 0

    /** Ticks spent clicking in one run. */
    fun clickTicks(): Int {
        val bursts = (totalClicks() + clicksPerTick - 1) / clicksPerTick
        return bursts + (bursts - 1) * burstDelay
    }

    fun save() {
        clamp()
        runCatching { Files.writeString(path, gson.toJson(this)) }
    }

    companion object {
        const val MAX_CYCLES = 50
        const val MAX_CLICKS_PER_TICK = 8
        const val MAX_DELAY = 20
        const val MAX_WAIT = 40

        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val path: Path
            get() = FabricLoader.getInstance().configDir.resolve("slotcycler.json")

        /** The live settings. Replaced wholesale by [reset]. */
        var current: Settings = load()
            private set

        private fun load(): Settings =
            runCatching { gson.fromJson(Files.readString(path), Settings::class.java) }
                .getOrNull()?.also { it.clamp() } ?: Settings()

        fun reset() {
            current = Settings()
            current.save()
        }
    }
}
