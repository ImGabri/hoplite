package slotcycler

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.widget.SliderWidget
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.text.Text
import net.minecraft.util.Util
import net.minecraft.util.math.MathHelper
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Settings screen: sliders and switches on the left and right, live preview below. */
class ConfigScreen : Screen(Text.literal("Slot Cycler")) {

    private companion object {
        const val PANEL_W = 392
        const val PANEL_H = 244
        const val COL_W = 180
        const val ROW_H = 18
        const val ROW_PITCH = 22

        const val OPAQUE = 0xFF000000.toInt()
        const val PANEL_BG = 0xF00C0D16.toInt()
        const val TRACK = 0xFF1B1E2E.toInt()
        const val TRACK_HOVER = 0xFF252A40.toInt()
        const val TEXT = 0xFFE8ECFF.toInt()
        const val TEXT_DIM = 0xFF8A90B0.toInt()
        const val ACCENT_A = 0xFF35E0FF.toInt()
        const val ACCENT_B = 0xFFB65CFF.toInt()
        const val WARN = 0xFFFF6B7A.toInt()
        const val OK = 0xFF5CFFA0.toInt()

        /** The preview plays this many times slower than the real run. */
        const val PREVIEW_SLOWDOWN = 4
        const val MS_PER_TICK = 50

        fun hue(h: Float, s: Float = 0.75f, v: Float = 1f): Int =
            MathHelper.hsvToRgb(((h % 1f) + 1f) % 1f, s, v) or OPAQUE

        fun lerpColor(t: Float, a: Int, b: Int): Int {
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t).roundToInt().coerceIn(0, 255) shl shift
            }
            return ch(24) or ch(16) or ch(8) or ch(0)
        }

        /** A filled rectangle with its four corner pixels cut off. */
        fun DrawContext.roundRect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
            fill(x1 + 1, y1, x2 - 1, y2, color)
            fill(x1, y1 + 1, x1 + 1, y2 - 1, color)
            fill(x2 - 1, y1 + 1, x2, y2 - 1, color)
        }
    }

    private val s get() = Settings.current
    private val openedAt = Util.getMeasuringTimeMs()
    private var px = 0
    private var py = 0

    private fun seconds(): Float = (Util.getMeasuringTimeMs() - openedAt) / 1000f

    override fun init() {
        px = (width - PANEL_W) / 2
        py = (height - PANEL_H) / 2
        val left = px + 12
        val right = px + PANEL_W - 12 - COL_W
        var y = py + 42

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

        y = py + 42
        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            addDrawableChild(FancyToggle(right, y, label, get, set))
            y += ROW_PITCH
        }
        toggle("Ghost clicks (copy in both)", { s.ghostClicks }, { s.ghostClicks = it })
        toggle("Keep ghost visible", { s.keepGhost }, { s.keepGhost = it })
        toggle("Real copy (server mod)", { s.realCopy }, { s.realCopy = it })
        toggle("Check for ghost items", { s.ghostCheck }, { s.ghostCheck = it })
        toggle("Drop surplus (Q)", { s.dropSurplus }, { s.dropSurplus = it })
        toggle("Finish in slot B", { s.endInB }, { s.endInB = it })

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

    // ---------------------------------------------------------------- drawing

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val t = seconds()
        // Fade the whole screen in over the first quarter second.
        val fade = (t / 0.25f).coerceIn(0f, 1f)
        val top = ((0xC0 * fade).toInt() shl 24) or 0x05060F
        val bottom = ((0xD8 * fade).toInt() shl 24) or 0x1A0B2E
        context.fillGradient(0, 0, width, height, top, bottom)
        drawParticles(context, t)

        // Soft glow, then the panel, then a border whose colours travel round it.
        for (i in 3 downTo 1) {
            val glow = lerpColor((sin(t * 1.5f) + 1f) / 2f, ACCENT_A, ACCENT_B)
            context.roundRect(px - i * 2, py - i * 2, px + PANEL_W + i * 2, py + PANEL_H + i * 2,
                (glow and 0xFFFFFF) or ((10 - i * 2) shl 24))
        }
        context.fill(px, py, px + PANEL_W, py + PANEL_H, PANEL_BG)
        drawBorder(context, t)

        drawTitle(context, t)
        context.fillGradient(px + 12, py + 35, px + PANEL_W - 12, py + 36, 0x6035E0FF, 0x60B65CFF)
    }

    private fun drawParticles(context: DrawContext, t: Float) {
        for (i in 0 until 48) {
            val seed = i * 97.13f
            val speed = 6f + (i % 7) * 3f
            val x = ((seed * 31f + t * speed * 0.4f) % (width + 20)) - 10
            val y = height - ((seed * 17f + t * speed) % (height + 20))
            val twinkle = (sin(t * 2f + i) + 1f) / 2f
            val alpha = (40 + 120 * twinkle).toInt()
            val size = 1 + i % 2
            val color = (hue(0.5f + (i % 10) * 0.03f, 0.5f) and 0xFFFFFF) or (alpha shl 24)
            context.fill(x.toInt(), y.toInt(), x.toInt() + size, y.toInt() + size, color)
        }
    }

    private fun drawBorder(context: DrawContext, t: Float) {
        val seg = 6
        val perimeter = (PANEL_W + PANEL_H) * 2f
        fun color(distance: Int) = hue(0.5f + 0.18f * sin((distance / perimeter) * 6.283f * 2f - t * 2f))
        var x = 0
        while (x < PANEL_W) {
            val w = minOf(seg, PANEL_W - x)
            context.fill(px + x, py, px + x + w, py + 1, color(x))
            context.fill(px + x, py + PANEL_H - 1, px + x + w, py + PANEL_H,
                color(PANEL_W * 2 + PANEL_H - x))
            x += seg
        }
        var y = 0
        while (y < PANEL_H) {
            val h = minOf(seg, PANEL_H - y)
            context.fill(px + PANEL_W - 1, py + y, px + PANEL_W, py + y + h, color(PANEL_W + y))
            context.fill(px, py + y, px + 1, py + y + h, color((perimeter - y).toInt()))
            y += seg
        }
    }

    private fun drawTitle(context: DrawContext, t: Float) {
        val title = "S L O T   C Y C L E R"
        var x = px + (PANEL_W - textRenderer.getWidth(title)) / 2
        title.forEachIndexed { i, c ->
            val ch = c.toString()
            val bob = (sin(t * 3f - i * 0.35f) * 1.5f).roundToInt()
            context.drawTextWithShadow(textRenderer, ch, x, py + 9 + bob, hue(0.5f + i * 0.012f + t * 0.08f, 0.55f))
            x += textRenderer.getWidth(ch)
        }
        context.drawCenteredTextWithShadow(
            textRenderer, "ghost items · surplus drop · timing", px + PANEL_W / 2, py + 23, TEXT_DIM
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        drawPreview(context)
    }

    /** A miniature hotbar showing the configured run in slow motion, plus totals. */
    private fun drawPreview(context: DrawContext) {
        val boxX = px + PANEL_W - 12 - COL_W
        val boxY = py + 174
        context.roundRect(boxX, boxY, boxX + COL_W, boxY + 44, TRACK)

        val slot = 18
        val barX = boxX + (COL_W - slot * 9) / 2
        val barY = boxY + 12
        for (i in 0 until 9) {
            val x = barX + i * slot
            val accent = when (i + 1) {
                s.slotA -> ACCENT_A
                s.slotB -> ACCENT_B
                else -> 0xFF2C3148.toInt()
            }
            context.fill(x + 1, barY + 1, x + slot - 1, barY + slot - 1, 0xFF10121C.toInt())
            context.drawStrokedRectangle(x + 1, barY + 1, slot - 2, slot - 2, accent)
            context.drawCenteredTextWithShadow(textRenderer, "${i + 1}", x + slot / 2, barY - 9,
                if (accent == 0xFF2C3148.toInt()) TEXT_DIM else accent)
        }

        if (s.slotA == s.slotB) {
            context.drawCenteredTextWithShadow(textRenderer, "Slot A and B must differ",
                boxX + COL_W / 2, boxY + 33, WARN)
            return
        }

        // Replay the click schedule in slow motion: where is the stack after `step` clicks?
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
            ?: ItemStack(Items.DIAMOND)
        context.drawItem(held, ix, iy)
        if (s.ghostClicks && step == total) {
            // A ghost run ends with a washed-out copy in the other slot.
            val gx = if (ix == ax) bx else ax
            context.drawItem(held, gx, barY + 1)
            context.fill(gx, barY + 1, gx + 16, barY + 17, 0x8010121C.toInt())
        }

        val ms = runTicks * MS_PER_TICK
        val mode = if (s.ghostClicks) "ghost" else "real"
        context.drawTextWithShadow(textRenderer, "$total $mode clicks", boxX + 6, boxY + 33,
            if (s.ghostClicks) ACCENT_B else OK)
        val time = "$runTicks ticks (${ms / 1000}.${(ms % 1000) / 100}s)"
        context.drawTextWithShadow(textRenderer, time, boxX + COL_W - 6 - textRenderer.getWidth(time),
            boxY + 33, TEXT)
    }

    // ---------------------------------------------------------------- widgets

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

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            val lit = isHovered || isFocused
            context.roundRect(x, y, x + width, y + height, if (lit) TRACK_HOVER else TRACK)

            // Fill up to the snapped value so the bar steps with the number.
            val fraction = (get() - min).toFloat() / (max - min)
            val fillW = (fraction * (width - 4)).roundToInt()
            if (fillW > 0) {
                val end = lerpColor(fraction, ACCENT_A, ACCENT_B)
                for (i in 0 until fillW) {
                    val c = lerpColor(i.toFloat() / (width - 4), ACCENT_A, end)
                    context.fill(x + 2 + i, y + height - 4, x + 3 + i, y + height - 2,
                        (c and 0xFFFFFF) or (if (lit) OPAQUE else 0xC0000000.toInt()))
                }
            }
            val knobX = x + 2 + fillW
            context.fill(knobX - 1, y + height - 6, knobX + 1, y + height, 0xFFFFFFFF.toInt())

            context.drawTextWithShadow(textRenderer, label, x + 5, y + 2, if (lit) TEXT else TEXT_DIM)
            val text = show(get())
            context.drawTextWithShadow(textRenderer, text, x + width - 5 - textRenderer.getWidth(text),
                y + 2, TEXT)
        }
    }

    private inner class FancyToggle(
        x: Int, y: Int, private val label: String,
        private val get: () -> Boolean, private val set: (Boolean) -> Unit
    ) : ClickableWidget(x, y, COL_W, ROW_H, Text.literal(label)) {

        /** 0 = off, 1 = on; eases towards the real value so the knob slides. */
        private var anim = if (get()) 1f else 0f

        override fun onClick(click: Click, doubled: Boolean) {
            set(!get())
        }

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            val target = if (get()) 1f else 0f
            anim += (target - anim) * (0.35f * delta).coerceAtMost(1f)
            if (abs(target - anim) < 0.01f) anim = target

            val lit = isHovered || isFocused
            context.roundRect(x, y, x + width, y + height, if (lit) TRACK_HOVER else TRACK)
            context.drawTextWithShadow(textRenderer, label, x + 5, y + 5, if (lit) TEXT else TEXT_DIM)

            val pillW = 24
            val pillX = x + width - 5 - pillW
            val pillY = y + 4
            context.roundRect(pillX, pillY, pillX + pillW, pillY + 10,
                lerpColor(anim, 0xFF30344A.toInt(), lerpColor(0.5f, ACCENT_A, ACCENT_B)))
            val knobX = pillX + 2 + ((pillW - 12) * anim).roundToInt()
            context.roundRect(knobX, pillY + 1, knobX + 8, pillY + 9, 0xFFFFFFFF.toInt())
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }

    private inner class FancyButton(
        x: Int, y: Int, w: Int, private val label: String,
        private val primary: Boolean, private val action: () -> Unit
    ) : ClickableWidget(x, y, w, 16, Text.literal(label)) {

        override fun onClick(click: Click, doubled: Boolean) = action()

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            val lit = isHovered || isFocused
            if (primary) {
                val shift = (sin(seconds() * 2f) + 1f) / 2f
                val a = lerpColor(shift, ACCENT_A, ACCENT_B)
                val b = lerpColor(shift, ACCENT_B, ACCENT_A)
                for (i in 0 until width) {
                    var c = lerpColor(i.toFloat() / width, a, b)
                    if (!lit) c = lerpColor(0.35f, c, OPAQUE)
                    context.fill(x + i, y + if (i == 0 || i == width - 1) 1 else 0,
                        x + i + 1, y + height - if (i == 0 || i == width - 1) 1 else 0, c)
                }
            } else {
                context.roundRect(x, y, x + width, y + height, if (lit) TRACK_HOVER else TRACK)
                if (lit) context.drawStrokedRectangle(x, y, width, height, ACCENT_A)
            }
            context.drawCenteredTextWithShadow(textRenderer, label, x + width / 2, y + 4,
                if (primary || lit) 0xFFFFFFFF.toInt() else TEXT)
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }
}
