package slotcycler

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

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
    var lowHealth = true
    var zoom = true
    var posInfo: Pos? = null
    var posEffects: Pos? = null
    var posArmor: Pos? = null
    var posTracker: Pos? = null
    var fpsBoost = false
    var menuFx = true
    var savedVideo: SavedVideo? = null

    class Pos(var x: Float = 0f, var y: Float = 0f)

    fun resetHud() {
        posInfo = Pos(0f, 0f)
        posEffects = Pos(0f, 0.09f)
        posArmor = Pos(1f, 1f)
        posTracker = Pos(1f, 0.5f)
    }

    class SavedVideo {
        var preset: String? = null
        var particles: String? = null
        var clouds: String? = null
        var shadows = true
        var blend = 2
        var transparency = false
        var leaves = true
        var vignette = true
    }

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
        if (posInfo == null || posEffects == null || posArmor == null || posTracker == null) resetHud()
        for (p in listOfNotNull(posInfo, posEffects, posArmor, posTracker)) {
            p.x = if (p.x.isFinite()) p.x.coerceIn(0f, 1f) else 0f
            p.y = if (p.y.isFinite()) p.y.coerceIn(0f, 1f) else 0f
        }
    }

    fun itemModeIndex(): Int = ITEM_MODES.indexOf(itemMode).coerceAtLeast(0)

    fun totalClicks(): Int = cycles * 4 + if (endInB) 2 else 0

    fun clickTicks(): Int {
        val bursts = (totalClicks() + clicksPerTick - 1) / clicksPerTick
        return bursts + (bursts - 1) * burstDelay
    }

    fun save() {
        clamp()
        val json = gson.toJson(this)
        if (json == lastWritten) return
        lastWritten = json
        writer.execute { runCatching { Files.writeString(path, json) } }
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
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "slotcycler-config").apply { isDaemon = true } }
        private var lastWritten: String? = null
        private val path: Path
            get() = FabricLoader.getInstance().configDir.resolve("slotcycler.json")

        var current: Settings = load()
            private set

        private fun load(): Settings =
            runCatching { gson.fromJson(Files.readString(path), Settings::class.java) }
                .getOrNull()?.also { it.clamp() } ?: Settings().also { it.clamp() }

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
                lowHealth = old.lowHealth
                zoom = old.zoom
                posInfo = old.posInfo
                posEffects = old.posEffects
                posArmor = old.posArmor
                posTracker = old.posTracker
                fpsBoost = old.fpsBoost
                menuFx = old.menuFx
                savedVideo = old.savedVideo
            }
            current.clamp()
            current.save()
        }
    }
}
