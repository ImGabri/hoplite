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
import kotlin.math.abs
import kotlin.math.roundToInt

class ConfigScreen : Screen(Text.literal("Slot Cycler")) {

    private companion object {
        const val PANEL_W = 392
        const val PANEL_H = 264
        const val COL_W = 180
        const val ROW_H = 18
        const val ROW_PITCH = 22
        const val ROWS_TOP = 44
        const val SWEEP_PARTS = 12

        const val OPAQUE = 0xFF000000.toInt()
        const val PANEL_BG = 0xF00C0D16.toInt()
        const val TRACK = 0xFF1B1E2E.toInt()
        const val TRACK_HOVER = 0xFF252A40.toInt()
        const val TEXT = 0xFFE8ECFF.toInt()
        const val TEXT_DIM = 0xFF8A90B0.toInt()
        const val ACCENT_A = 0xFF35E0FF.toInt()
        const val ACCENT_B = 0xFFB65CFF.toInt()
        const val ACCENT_MID = 0xFF769EFF.toInt()
        const val EDGE = 0xFF2C3148.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TITLE = "S L O T   C Y C L E R"
        const val WARN = 0xFFFF6B7A.toInt()
        const val OK = 0xFF5CFFA0.toInt()

        const val PREVIEW_SLOWDOWN = 4
        const val MS_PER_TICK = 50

        fun lerpColor(t: Float, a: Int, b: Int): Int {
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t).roundToInt().coerceIn(0, 255) shl shift
            }
            return ch(24) or ch(16) or ch(8) or ch(0)
        }

        fun alpha(color: Int, a: Int): Int = (color and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)

        fun ease(current: Float, target: Float, delta: Float): Float {
            val next = current + (target - current) * (0.35f * delta).coerceAtMost(1f)
            return if (abs(target - next) < 0.01f) target else next
        }

        fun pretty(id: String): String =
            id.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

        fun DrawContext.sweep(x1: Int, y1: Int, x2: Int, y2: Int, from: Int, to: Int) {
            val w = x2 - x1
            if (w <= 0) return
            val parts = minOf(SWEEP_PARTS, w)
            for (i in 0 until parts) {
                fill(x1 + w * i / parts, y1, x1 + w * (i + 1) / parts, y2,
                    lerpColor((i + 0.5f) / parts, from, to))
            }
        }

        fun DrawContext.roundRect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
            fill(x1 + 1, y1, x2 - 1, y2, color)
            fill(x1, y1 + 1, x1 + 1, y2 - 1, color)
            fill(x2 - 1, y1 + 1, x2, y2 - 1, color)
        }
    }

    private val s get() = Settings.current
    private val openedAt = Util.getMeasuringTimeMs()
    private val placeholder = ItemStack(Items.DIAMOND)
    private var px = 0
    private var py = 0

    private fun seconds(): Float = (Util.getMeasuringTimeMs() - openedAt) / 1000f

    override fun init() {
        px = (width - PANEL_W) / 2
        py = (height - PANEL_H) / 2
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

        val by = py + PANEL_H - 22
        addDrawableChild(FancyButton(left, by, 70, "Reset", false) {
            Settings.reset()
            clearAndInit()
        })
        addDrawableChild(FancyButton(left + 76, by, 84, "Clear ghosts", false) {
            SlotCyclerMod.clearRequested = true
        })
        addDrawableChild(FancyButton(px + PANEL_W - 12 - 70, by, 70, "Done", false) { close() })
        addDrawableChild(FancyButton(px + PANEL_W - 12 - 70 - 6 - 110, by, 110, "Save & Run", true) {
            close()
            SlotCyclerMod.runRequested = true
        })
    }

    override fun close() {
        s.save()
        super.close()
    }

    override fun shouldPause() = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val fade = (seconds() / 0.2f).coerceIn(0f, 1f)
        context.fillGradient(0, 0, width, height,
            alpha(0x05060F, (0xB8 * fade).toInt()), alpha(0x0C0D16, (0xD8 * fade).toInt()))

        context.roundRect(px - 2, py - 2, px + PANEL_W + 2, py + PANEL_H + 2, alpha(0, (0x30 * fade).toInt()))
        context.roundRect(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, EDGE)
        context.fill(px, py, px + PANEL_W, py + PANEL_H, PANEL_BG)
        context.sweep(px, py, px + PANEL_W, py + 1, ACCENT_A, ACCENT_B)

        val mid = px + PANEL_W / 2
        context.drawCenteredTextWithShadow(textRenderer, TITLE, mid, py + 10, TEXT)
        val ghost = s.ghostClicks
        val line = if (ghost) "Ghost mode \u00B7 client-side, display-only" else "Real mode \u00B7 server-authoritative"
        val lineX = mid - textRenderer.getWidth(line) / 2
        context.fill(lineX - 7, py + 26, lineX - 3, py + 30, if (ghost) ACCENT_B else OK)
        context.drawTextWithShadow(textRenderer, line, lineX, py + 24, TEXT_DIM)
        context.fill(px + 12, py + 37, px + PANEL_W - 12, py + 38, EDGE)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        drawPreview(context)
    }

    private fun drawPreview(context: DrawContext) {
        val boxX = px + PANEL_W - 12 - COL_W
        val boxY = py + 176
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
            drawRow(context, x, y, width, height, glow)

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

    private fun drawRow(context: DrawContext, x: Int, y: Int, w: Int, h: Int, glow: Float) {
        context.roundRect(x, y, x + w, y + h, lerpColor(glow, TRACK, TRACK_HOVER))
        if (glow > 0f) {
            val bar = (h * glow).roundToInt()
            val top = y + (h - bar) / 2
            context.fill(x, top, x + 2, top + bar, alpha(ACCENT_MID, (255 * glow).toInt()))
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
            drawRow(context, x, y, width, height, glow)
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
            drawRow(context, x, y, width, height, glow)
            context.drawTextWithShadow(textRenderer, label, x + 6, y + 5,
                lerpColor(maxOf(glow, anim * 0.6f), TEXT_DIM, TEXT))

            val pillW = 24
            val pillX = x + width - 5 - pillW
            val pillY = y + 4
            context.roundRect(pillX, pillY, pillX + pillW, pillY + 10, lerpColor(anim, 0xFF30344A.toInt(), ACCENT_MID))
            val knobX = pillX + 2 + ((pillW - 12) * anim).roundToInt()
            context.roundRect(knobX, pillY + 1, knobX + 8, pillY + 9, WHITE)
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }

    private inner class FancyButton(
        x: Int, y: Int, w: Int, private val label: String,
        private val primary: Boolean, private val action: () -> Unit
    ) : ClickableWidget(x, y, w, 16, Text.literal(label)) {

        private var glow = 0f

        override fun onClick(click: Click, doubled: Boolean) = action()

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            glow = ease(glow, if (isHovered || isFocused) 1f else 0f, delta)
            if (primary) {
                val dim = 0.3f * (1f - glow)
                context.sweep(x + 1, y, x + width - 1, y + height,
                    lerpColor(dim, ACCENT_A, OPAQUE), lerpColor(dim, ACCENT_B, OPAQUE))
            } else {
                context.roundRect(x, y, x + width, y + height, lerpColor(glow, TRACK, TRACK_HOVER))
                if (glow > 0f) context.drawStrokedRectangle(x, y, width, height, alpha(ACCENT_MID, (255 * glow).toInt()))
            }
            context.drawCenteredTextWithShadow(textRenderer, label, x + width / 2, y + 4,
                if (primary) WHITE else lerpColor(glow, TEXT, WHITE))
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }
}
