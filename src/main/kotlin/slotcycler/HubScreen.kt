package slotcycler

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.text.Text
import net.minecraft.util.Util
import slotcycler.Ui.ACCENT_MID
import slotcycler.Ui.EDGE
import slotcycler.Ui.OK
import slotcycler.Ui.TEXT
import slotcycler.Ui.TEXT_DIM
import slotcycler.Ui.TEXT_FAINT
import slotcycler.Ui.TITLE
import slotcycler.Ui.TRACK
import slotcycler.Ui.backdrop
import slotcycler.Ui.panel
import slotcycler.Ui.roundRect
import slotcycler.Ui.shifted
import slotcycler.Ui.slide

class HubScreen : Screen(Text.literal("Slot Cycler")) {

    private companion object {
        const val PANEL_W = 392
        const val PANEL_H = 232
        const val SIDE_W = 100
        const val TOP = 46
        const val TRADING = 0
        const val HUD = 1
        const val UTILITY = 2
        const val PERFORMANCE = 3

        val TABS = listOf(
            "Trading" to Items.EMERALD,
            "HUD" to Items.SPYGLASS,
            "Utility" to Items.BELL,
            "Performance" to Items.FEATHER
        )
        val SUBTITLES = listOf(
            "Trading and containers",
            "On-screen information",
            "Alerts and helpers",
            "Frame rate"
        )
        var tab = TRADING
    }

    private val s get() = Settings.current
    private val openedAt = Util.getMeasuringTimeMs()
    private var px = 0
    private var py = 0
    private var cx = 0
    private var cw = 0

    private fun seconds(): Float = (Util.getMeasuringTimeMs() - openedAt) / 1000f
    private fun fade(): Float = (seconds() / 0.2f).coerceIn(0f, 1f)

    override fun init() {
        px = (width - PANEL_W) / 2
        py = maxOf(4, (height - PANEL_H) / 2)
        cx = px + 12 + SIDE_W + 14
        cw = px + PANEL_W - 12 - cx

        TABS.forEachIndexed { i, (label, item) ->
            addDrawableChild(Ui.Tab(px + 12, py + TOP + i * 22, SIDE_W, label, ItemStack(item), { tab == i }) {
                tab = i
                clearAndInit()
            })
        }
        addDrawableChild(Ui.Tab(px + 12, py + TOP + TABS.size * 22 + 9, SIDE_W, "Slot Cycler ›",
            ItemStack(Items.HOPPER), { false }) {
            s.save()
            client?.setScreen(ConfigScreen(this))
        })

        var y = py + TOP
        fun option(label: String, detail: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            addDrawableChild(Ui.Option(cx, y, cw, label, detail, get, set))
            y += 29
        }

        when (tab) {
            TRADING -> {
                option("Trade max button", "Repeat a trade until you run out", { s.tradeMax }, { s.tradeMax = it })
                option("Container buttons", "Take all, Deposit and Stack on chests",
                    { s.containerButtons }, { s.containerButtons = it })
            }
            HUD -> {
                option("Armour durability", "Uses left on armour and held items", { s.armorHud }, { s.armorHud = it })
                option("Item tracker", "Counts the items listed below", { s.itemTracker }, { s.itemTracker = it })
                val field = TextFieldWidget(textRenderer, cx + 1, y, cw - 2, 16, Text.literal("Tracked items"))
                field.setMaxLength(96)
                field.text = s.trackedItems ?: ""
                field.setChangedListener { s.trackedItems = it }
                addDrawableChild(field)
                y += 21
                option("Effect timers", "Active potion effects with time left", { s.effectTimers }, { s.effectTimers = it })
                option("Info line", "Coordinates, facing, ping and FPS", { s.infoHud }, { s.infoHud = it })
            }
            UTILITY -> {
                option("Durability alert", "Warns below 10% durability", { s.durabilityAlert }, { s.durabilityAlert = it })
                option("Death coordinates", "Prints where you died in chat", { s.deathCoords }, { s.deathCoords = it })
                option("Fullbright", "Client-side night vision", { s.fullbright }, { s.fullbright = it })
            }
            PERFORMANCE -> {
                option("FPS boost preset", "Lighter particles, clouds and leaves", { s.fpsBoost }, Perf::setBoost)
            }
        }

        addDrawableChild(Ui.Button(px + PANEL_W - 12 - 70, py + PANEL_H - 22, 70, 16, "Done", true) { close() })
    }

    override fun close() {
        s.save()
        super.close()
    }

    override fun shouldPause() = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val fade = fade()
        context.backdrop(width, height, fade)
        context.shifted(slide(fade)) { drawPanel(context, fade) }
    }

    private fun drawPanel(context: DrawContext, fade: Float) {
        context.panel(px, py, PANEL_W, PANEL_H, fade, seconds())
        val mid = px + PANEL_W / 2
        context.drawCenteredTextWithShadow(textRenderer, TITLE, mid, py + 10, TEXT)
        context.drawCenteredTextWithShadow(textRenderer, SUBTITLES[tab], mid, py + 24, TEXT_DIM)
        context.fill(px + 12, py + 37, px + PANEL_W - 12, py + 38, EDGE)
        context.fill(px + 12 + SIDE_W + 6, py + TOP, px + 12 + SIDE_W + 7, py + PANEL_H - 30, EDGE)
        context.fill(px + 16, py + TOP + TABS.size * 22 + 3, px + 8 + SIDE_W, py + TOP + TABS.size * 22 + 4, EDGE)
        context.fill(px + 12, py + PANEL_H - 29, px + PANEL_W - 12, py + PANEL_H - 28, EDGE)
        context.drawTextWithShadow(textRenderer, "v${SlotCyclerMod.VERSION}", px + 12, py + PANEL_H - 18, TEXT_FAINT)
        if (tab == PERFORMANCE) drawPerformance(context)
    }

    private fun drawPerformance(context: DrawContext) {
        var y = py + TOP + 33
        context.drawTextWithShadow(textRenderer, "PERFORMANCE MODS", cx + 1, y, ACCENT_MID)
        val fps = "${client?.currentFps ?: 0} fps"
        context.drawTextWithShadow(textRenderer, fps, cx + cw - textRenderer.getWidth(fps), y, TEXT_DIM)
        y += 12
        val half = (cw - 4) / 2
        Perf.MODS.forEachIndexed { i, (_, name) ->
            val x = cx + (i % 2) * (half + 4)
            val rowY = y + (i / 2) * 18
            val on = Perf.installed[i]
            context.roundRect(x, rowY, x + half, rowY + 15, TRACK)
            context.fill(x + 6, rowY + 6, x + 9, rowY + 9, if (on) OK else TEXT_FAINT)
            context.drawTextWithShadow(textRenderer, name, x + 14, rowY + 4, if (on) TEXT else TEXT_DIM)
        }
        y += 18 * ((Perf.MODS.size + 1) / 2) + 4
        context.drawTextWithShadow(textRenderer, "Green mods are installed. Sodium and Lithium", cx + 1, y, TEXT_FAINT)
        context.drawTextWithShadow(textRenderer, "give the largest frame rate gains.", cx + 1, y + 10, TEXT_FAINT)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.shifted(slide(fade())) { super.render(context, mouseX, mouseY, delta) }
    }
}
