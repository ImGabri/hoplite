package slotcycler

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.CloudRenderMode
import net.minecraft.client.option.GraphicsMode
import net.minecraft.particle.ParticlesMode

object Perf {
    val MODS = listOf(
        "sodium" to "Sodium",
        "lithium" to "Lithium",
        "ferritecore" to "FerriteCore",
        "immediatelyfast" to "ImmediatelyFast",
        "entityculling" to "Entity Culling",
        "moreculling" to "More Culling"
    )

    val installed: List<Boolean> by lazy {
        val loader = FabricLoader.getInstance()
        MODS.map { loader.isModLoaded(it.first) }
    }

    fun setBoost(on: Boolean) {
        val s = Settings.current
        if (on == s.fpsBoost) return
        val client = MinecraftClient.getInstance()
        val o = client.options
        if (on) {
            s.savedVideo = Settings.SavedVideo().apply {
                preset = o.preset.value.name
                particles = o.particles.value.name
                clouds = o.cloudRenderMode.value.name
                shadows = o.entityShadows.value
                blend = o.biomeBlendRadius.value
                transparency = o.improvedTransparency.value
                leaves = o.cutoutLeaves.value
                vignette = o.vignette.value
            }
            o.preset.value = GraphicsMode.CUSTOM
            o.particles.value = ParticlesMode.MINIMAL
            o.cloudRenderMode.value = CloudRenderMode.OFF
            o.entityShadows.value = false
            o.biomeBlendRadius.value = 0
            o.improvedTransparency.value = false
            o.cutoutLeaves.value = false
            o.vignette.value = false
        } else {
            s.savedVideo?.let { v ->
                o.particles.value = ParticlesMode.entries.firstOrNull { it.name == v.particles } ?: ParticlesMode.ALL
                o.cloudRenderMode.value = CloudRenderMode.entries.firstOrNull { it.name == v.clouds } ?: CloudRenderMode.FANCY
                o.entityShadows.value = v.shadows
                o.biomeBlendRadius.value = v.blend
                o.improvedTransparency.value = v.transparency
                o.cutoutLeaves.value = v.leaves
                o.vignette.value = v.vignette
                o.preset.value = GraphicsMode.entries.firstOrNull { it.name == v.preset } ?: GraphicsMode.CUSTOM
            }
            s.savedVideo = null
        }
        s.fpsBoost = on
        o.write()
        if (client.world != null) client.worldRenderer.reload()
    }
}
