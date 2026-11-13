package slotcycler

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.SliderWidget
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Util
import slotcycler.Ui.TRACK
import slotcycler.Ui.TEXT
import slotcycler.Ui.TEXT_DIM
import slotcycler.Ui.ACCENT_A
import slotcycler.Ui.ACCENT_B
import slotcycler.Ui.EDGE
import slotcycler.Ui.WHITE
import slotcycler.Ui.WARN
import slotcycler.Ui.OK
import slotcycler.Ui.pill
import slotcycler.Ui.title
import slotcycler.Ui.lerpColor
import slotcycler.Ui.ease
import slotcycler.Ui.pretty
import slotcycler.Ui.sweep
import slotcycler.Ui.backdrop
import slotcycler.Ui.panel
import slotcycler.Ui.shifted
import slotcycler.Ui.slide
import slotcycler.Ui.roundRect
import slotcycler.Ui.row
import kotlin.math.roundToInt

class ConfigScreen(private val parent: Screen? = null) : Screen(Text.literal("Slot Cycler")) {

    private companion object {
        const val PANEL_W = 392
        const val PANEL_H = 232
        const val COL_W = 180
        const val ROW_H = 18
        const val ROW_PITCH = 20
        const val ROWS_TOP = 44
        
        const val PREVIEW_SLOWDOWN = 4
        const val MS_PER_TICK = 50

    }

    private val s get() = Settings.current
    private val openedAt = Util.getMeasuringTimeMs()
    private val placeholder = ItemStack(Items.DIAMOND)
    private var px = 0
    private var py = 0

    private fun seconds(): Float = (Util.getMeasuringTimeMs() - openedAt) / 1000f

    override fun init() {
        px = (width - PANEL_W) / 2
        py = maxOf(4, (height - PANEL_H) / 2)
        val left = px + 12
        val right = px + PANEL_W - 12 - COL_W
        var y = py + ROWS_TOP

        fun slider(label: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit,
                   show: (Int) -> String = { it.toString() }) {
            addDrawableChild(FancySlider(left, y, label, min, max, get, set, show))
            y += ROW_PITCH
        }
        fun ticks(n: Int) = if (n == 0) "none" else "$n t"

        slider("Slot A (start)", 1, 9, { s.slotA }, { s.slotA = it })
        slider("Slot B (target)", 1, 9, { s.slotB }, { s.slotB = it })
        slider("Cycles", 1, Settings.MAX_CYCLES, { s.cycles }, { s.cycles = it })
        slider("Clicks per tick", 1, Settings.MAX_CLICKS_PER_TICK,
            { s.clicksPerTick }, { s.clicksPerTick = it })
        slider("Delay between bursts", 0, Settings.MAX_DELAY,
            { s.burstDelay }, { s.burstDelay = it }, ::ticks)
        slider("Settle time", 0, Settings.MAX_WAIT, { s.settleTicks }, { s.settleTicks = it }, ::ticks)
        slider("Ghost check wait", 2, Settings.MAX_WAIT, { s.verifyTicks }, { s.verifyTicks = it }, ::ticks)
        addDrawableChild(FancyCycler(left, y))

        y = py + ROWS_TOP
        fun toggle(label: String, hint: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            addDrawableChild(FancyToggle(right, y, label, hint, get, set))
            y += ROW_PITCH
        }
        toggle("Ghost clicks", "Clicks stay on the client. Display-only on remote servers.",
            { s.ghostClicks }, { s.ghostClicks = it })
        toggle("Keep ghost visible", "Redraw the display copy until you press Clear ghosts.",
            { s.keepGhost }, { s.keepGhost = it })
        toggle("Real copy", "Singleplayer or multiplayer creative only. Needs Ghost clicks.",
            { s.realCopy }, { s.realCopy = it })
        toggle("Ghost check", "Ask the server for the real inventory after the run.",
            { s.ghostCheck }, { s.ghostCheck = it })
        toggle("Drop surplus", "Drop anything slot A gained, like pressing Q.",
            { s.dropSurplus }, { s.dropSurplus = it })
        toggle("Finish in slot B", "One extra move so the stack ends in slot B.",
            { s.endInB }, { s.endInB = it })

        val by = py + PANEL_H - 20
        addDrawableChild(Ui.Button(left, by, 70, 16, "Reset", false) {
            Settings.reset()
            clearAndInit()
        })
        addDrawableChild(Ui.Button(left + 76, by, 84, 16, "Clear ghosts", false) {
            SlotCyclerMod.clearRequested = true
        })
        addDrawableChild(Ui.Button(px + PANEL_W - 12 - 70, by, 70, 16, "Done", false) { close() })
        addDrawableChild(Ui.Button(px + PANEL_W - 12 - 70 - 6 - 110, by, 110, 16, "Save & Run", true) {
            s.save()
            client?.setScreen(null)
            SlotCyclerMod.runRequested = true
        })
    }

    override fun close() {
        s.save()
        client?.setScreen(parent)
    }

    override fun shouldPause() = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val fade = (seconds() / 0.2f).coerceIn(0f, 1f)
        context.backdrop(width, height, fade, seconds())
        context.shifted(slide(fade)) { drawPanel(context, fade) }
    }

    private fun drawPanel(context: DrawContext, fade: Float) {
        context.panel(px, py, PANEL_W, PANEL_H, fade, seconds())

        val mid = px + PANEL_W / 2
        context.title(mid, py + 10, seconds())
        val ghost = s.ghostClicks
        val line = if (ghost) "Ghost mode \u00B7 client-side, display-only" else "Real mode \u00B7 server-authoritative"
        val lineX = mid - textRenderer.getWidth(line) / 2
        context.fill(lineX - 7, py + 26, lineX - 3, py + 30, if (ghost) ACCENT_B else OK)
        context.drawTextWithShadow(textRenderer, line, lineX, py + 24, TEXT_DIM)
        context.fill(px + 12, py + 37, px + PANEL_W - 12, py + 38, EDGE)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.shifted(slide((seconds() / 0.2f).coerceIn(0f, 1f))) {
            super.render(context, mouseX, mouseY, delta)
            drawPreview(context)
        }
    }

    private fun drawPreview(context: DrawContext) {
        val boxX = px + PANEL_W - 12 - COL_W
        val boxY = py + 166
        context.roundRect(boxX, boxY, boxX + COL_W, boxY + 44, TRACK)

        val slot = 18
        val barX = boxX + (COL_W - slot * 9) / 2
        val barY = boxY + 12
        for (i in 0 until 9) {
            val x = barX + i * slot
            val accent = when (i + 1) {
                s.slotA -> ACCENT_A
                s.slotB -> ACCENT_B
                else -> EDGE
            }
            context.fill(x + 1, barY + 1, x + slot - 1, barY + slot - 1, 0xFF10121C.toInt())
            context.drawStrokedRectangle(x + 1, barY + 1, slot - 2, slot - 2, accent)
            context.drawCenteredTextWithShadow(textRenderer, "${i + 1}", x + slot / 2, barY - 9,
                if (accent == EDGE) TEXT_DIM else accent)
        }

        if (s.slotA == s.slotB) {
            context.drawCenteredTextWithShadow(textRenderer, "Slot A and B must differ",
                boxX + COL_W / 2, boxY + 33, WARN)
            return
        }

        val total = s.totalClicks()
        val runTicks = s.clickTicks()
        val loop = runTicks + 6
        val tick = ((Util.getMeasuringTimeMs() - openedAt) / (MS_PER_TICK * PREVIEW_SLOWDOWN) % loop).toInt()
        val step = if (tick >= runTicks) total
        else minOf(total, (tick / (1 + s.burstDelay) + 1) * s.clicksPerTick)
        val ax = barX + (s.slotA - 1) * slot + 1
        val bx = barX + (s.slotB - 1) * slot + 1
        val (ix, iy) = when (step % 4) {
            0 -> ax to barY + 1
            2 -> bx to barY + 1
            else -> (ax + bx) / 2 to barY - 5
        }
        val held = client?.player?.inventory?.getStack(s.slotA - 1)?.takeIf { !it.isEmpty }
            ?: placeholder
        context.drawItem(held, ix, iy)
        if (s.ghostClicks && step == total) {
            val gx = if (ix == ax) bx else ax
            context.drawItem(held, gx, barY + 1)
            context.fill(gx, barY + 1, gx + 16, barY + 17, 0x8010121C.toInt())
        }

        val progress = (tick.toFloat() / runTicks).coerceIn(0f, 1f)
        context.sweep(boxX + 2, boxY + 42, boxX + 2 + ((COL_W - 4) * progress).roundToInt(), boxY + 43,
            ACCENT_A, ACCENT_B)

        val ms = runTicks * MS_PER_TICK
        val mode = if (s.ghostClicks) "ghost" else "real"
        context.drawTextWithShadow(textRenderer, "$total $mode clicks", boxX + 6, boxY + 33,
            if (s.ghostClicks) ACCENT_B else OK)
        val time = "$runTicks ticks (${ms / 1000}.${(ms % 1000) / 100}s)"
        context.drawTextWithShadow(textRenderer, time, boxX + COL_W - 6 - textRenderer.getWidth(time),
            boxY + 33, TEXT)
    }

    private inner class FancySlider(
        x: Int, y: Int, private val label: String,
        private val min: Int, private val max: Int,
        private val get: () -> Int, private val set: (Int) -> Unit,
        private val show: (Int) -> String
    ) : SliderWidget(x, y, COL_W, ROW_H, Text.empty(), (get() - min).toDouble() / (max - min)) {

        init {
            updateMessage()
        }

        override fun updateMessage() {
            message = Text.literal("$label: ${show(get())}")
        }

        override fun applyValue() {
            set(min + (value * (max - min)).roundToInt())
        }

        private var glow = 0f

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            val lit = isHovered || isFocused
            glow = ease(glow, if (lit) 1f else 0f, delta)
            context.row(x, y, width, height, glow)

            val fraction = (get() - min).toFloat() / (max - min)
            val span = width - 4
            val fillW = (fraction * span).roundToInt()
            context.fill(x + 2, y + height - 4, x + 2 + span, y + height - 2, 0xFF10121C.toInt())
            val knob = lerpColor(fraction, ACCENT_A, ACCENT_B)
            context.sweep(x + 2, y + height - 4, x + 2 + fillW, y + height - 2, ACCENT_A, knob)
            val knobX = x + 2 + fillW
            context.fill(knobX - 1, y + height - 6, knobX + 1, y + height, WHITE)

            context.drawTextWithShadow(textRenderer, label, x + 6, y + 2, lerpColor(glow, TEXT_DIM, TEXT))
            val text = show(get())
            context.drawTextWithShadow(textRenderer, text, x + width - 5 - textRenderer.getWidth(text),
                y + 2, TEXT)
        }
    }

    private inner class FancyCycler(x: Int, y: Int) :
        ClickableWidget(x, y, COL_W, ROW_H, Text.literal("Item filter")) {

        private var glow = 0f
        private val icons = Settings.ITEM_MODES.map {
            if (it == Settings.ANY_ITEM) ItemStack.EMPTY else ItemStack(Registries.ITEM.get(Identifier.ofVanilla(it)))
        }
        private val names = Settings.ITEM_MODES.map {
            "\u2039 " + (if (it == Settings.ANY_ITEM) "Any item" else pretty(it)) + " \u203A"
        }

        init {
            setTooltip(Tooltip.of(Text.literal("Only run when slot A holds this item. Click to change.")))
        }

        override fun onClick(click: Click, doubled: Boolean) {
            val modes = Settings.ITEM_MODES
            s.itemMode = modes[(s.itemModeIndex() + 1) % modes.size]
        }

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            glow = ease(glow, if (isHovered || isFocused) 1f else 0f, delta)
            context.row(x, y, width, height, glow)
            context.drawTextWithShadow(textRenderer, "Item filter", x + 6, y + 5, lerpColor(glow, TEXT_DIM, TEXT))

            val index = s.itemModeIndex()
            var right = x + width - 5
            if (!icons[index].isEmpty) {
                right -= 17
                context.drawItem(icons[index], right, y + 1)
            }
            val name = names[index]
            context.drawTextWithShadow(textRenderer, name, right - 2 - textRenderer.getWidth(name), y + 5, TEXT)
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }

    private inner class FancyToggle(
        x: Int, y: Int, private val label: String, hint: String,
        private val get: () -> Boolean, private val set: (Boolean) -> Unit
    ) : ClickableWidget(x, y, COL_W, ROW_H, Text.literal(label)) {

        private var anim = if (get()) 1f else 0f
        private var glow = 0f

        init {
            setTooltip(Tooltip.of(Text.literal(hint)))
        }

        override fun onClick(click: Click, doubled: Boolean) {
            set(!get())
        }

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            anim = ease(anim, if (get()) 1f else 0f, delta)
            glow = ease(glow, if (isHovered || isFocused) 1f else 0f, delta)
            context.row(x, y, width, height, glow)
            context.drawTextWithShadow(textRenderer, label, x + 6, y + 5,
                lerpColor(maxOf(glow, anim * 0.6f), TEXT_DIM, TEXT))

            context.pill(x + width - 29, y + 4, anim)
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }
}
