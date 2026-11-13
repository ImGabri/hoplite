package slotcycler

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

class Settings {
    var slotA = 1
    var slotB = 2
    var cycles = 3
    var clicksPerTick = 2
    var burstDelay = 0
    var settleTicks = 5
    var verifyTicks = 10
    var ghostClicks = false
    var keepGhost = true
    var realCopy = true
    var ghostCheck = true
    var dropSurplus = true
    var endInB = false
    var itemMode: String? = ANY_ITEM

    var tradeMax = true
    var containerButtons = true
    var armorHud = true
    var itemTracker = true
    var trackedItems: String? = DEFAULT_TRACKED
    var infoHud = false
    var effectTimers = true
    var durabilityAlert = true
    var fullbright = false
    var deathCoords = true

    fun trackedTerms(): List<String> =
        (trackedItems ?: "").split(',').map { it.trim().lowercase().replace(' ', '_') }.filter { it.isNotEmpty() }

    fun clamp() {
        slotA = slotA.coerceIn(1, 9)
        slotB = slotB.coerceIn(1, 9)
        cycles = cycles.coerceIn(1, MAX_CYCLES)
        clicksPerTick = clicksPerTick.coerceIn(1, MAX_CLICKS_PER_TICK)
        burstDelay = burstDelay.coerceIn(0, MAX_DELAY)
        settleTicks = settleTicks.coerceIn(0, MAX_WAIT)
        verifyTicks = verifyTicks.coerceIn(2, MAX_WAIT)
        if (itemMode !in ITEM_MODES) itemMode = ANY_ITEM
        if (trackedItems == null) trackedItems = DEFAULT_TRACKED
    }

    fun itemModeIndex(): Int = ITEM_MODES.indexOf(itemMode).coerceAtLeast(0)

    fun totalClicks(): Int = cycles * 4 + if (endInB) 2 else 0

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
        const val ANY_ITEM = "any_item"
        const val DEFAULT_TRACKED = "scrap, totem, golden_apple, ender_pearl"

        val ITEM_MODES = listOf(
            ANY_ITEM, "gold_ingot", "iron_ingot", "copper_ingot",
            "diamond", "emerald", "lapis_lazuli", "redstone"
        )

        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val path: Path
            get() = FabricLoader.getInstance().configDir.resolve("slotcycler.json")

        var current: Settings = load()
            private set

        private fun load(): Settings =
            runCatching { gson.fromJson(Files.readString(path), Settings::class.java) }
                .getOrNull()?.also { it.clamp() } ?: Settings()

        fun reset() {
            val old = current
            current = Settings().apply {
                tradeMax = old.tradeMax
                containerButtons = old.containerButtons
                armorHud = old.armorHud
                itemTracker = old.itemTracker
                trackedItems = old.trackedItems
                infoHud = old.infoHud
                effectTimers = old.effectTimers
                durabilityAlert = old.durabilityAlert
                fullbright = old.fullbright
                deathCoords = old.deathCoords
            }
            current.save()
        }
    }
}
