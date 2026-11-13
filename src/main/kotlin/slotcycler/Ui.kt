package slotcycler

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.text.Text
import kotlin.math.abs
import kotlin.math.roundToInt

object Ui {
    const val OPAQUE = 0xFF000000.toInt()
    const val PANEL_BG = 0xF00C0D16.toInt()
    const val CHIP_BG = 0xA00C0D16.toInt()
    const val TRACK = 0xFF1B1E2E.toInt()
    const val TRACK_HOVER = 0xFF252A40.toInt()
    const val TEXT = 0xFFE8ECFF.toInt()
    const val TEXT_DIM = 0xFF8A90B0.toInt()
    const val ACCENT_A = 0xFF35E0FF.toInt()
    const val ACCENT_B = 0xFFB65CFF.toInt()
    const val ACCENT_MID = 0xFF769EFF.toInt()
    const val EDGE = 0xFF2C3148.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val WARN = 0xFFFF6B7A.toInt()
    const val AMBER = 0xFFFFC857.toInt()
    const val OK = 0xFF5CFFA0.toInt()
    const val TITLE = "S L O T   C Y C L E R"
    private const val SWEEP_PARTS = 12

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

    fun DrawContext.row(x: Int, y: Int, w: Int, h: Int, glow: Float) {
        roundRect(x, y, x + w, y + h, lerpColor(glow, TRACK, TRACK_HOVER))
        if (glow > 0f) {
            val bar = (h * glow).roundToInt()
            val top = y + (h - bar) / 2
            fill(x, top, x + 2, top + bar, alpha(ACCENT_MID, (255 * glow).toInt()))
        }
    }

    fun DrawContext.panel(x: Int, y: Int, w: Int, h: Int, fade: Float) {
        roundRect(x - 2, y - 2, x + w + 2, y + h + 2, alpha(0, (0x30 * fade).toInt()))
        roundRect(x - 1, y - 1, x + w + 1, y + h + 1, EDGE)
        fill(x, y, x + w, y + h, PANEL_BG)
        sweep(x, y, x + w, y + 1, ACCENT_A, ACCENT_B)
    }

    private val font get() = MinecraftClient.getInstance().textRenderer

    class Button(
        x: Int, y: Int, w: Int, h: Int, private val label: String,
        private val primary: Boolean, private val action: () -> Unit
    ) : ClickableWidget(x, y, w, h, Text.literal(label)) {

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
                if (glow > 0f) {
                    context.drawStrokedRectangle(x, y, width, height, alpha(ACCENT_MID, (255 * glow).toInt()))
                }
            }
            context.drawCenteredTextWithShadow(font, label, x + width / 2, y + (height - 8) / 2,
                if (primary) WHITE else lerpColor(glow, TEXT, WHITE))
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }

    class Toggle(
        x: Int, y: Int, w: Int, private val label: String, hint: String,
        private val get: () -> Boolean, private val set: (Boolean) -> Unit
    ) : ClickableWidget(x, y, w, 18, Text.literal(label)) {

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
            context.drawTextWithShadow(font, label, x + 6, y + 5, lerpColor(maxOf(glow, anim * 0.6f), TEXT_DIM, TEXT))

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

    class Link(
        x: Int, y: Int, w: Int, private val label: String, private val detail: String,
        private val action: () -> Unit
    ) : ClickableWidget(x, y, w, 22, Text.literal(label)) {

        private var glow = 0f

        override fun onClick(click: Click, doubled: Boolean) = action()

        override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            glow = ease(glow, if (isHovered || isFocused) 1f else 0f, delta)
            context.row(x, y, width, height, glow)
            context.drawTextWithShadow(font, label, x + 8, y + 7, lerpColor(glow, TEXT, WHITE))
            context.drawTextWithShadow(font, detail, x + 14 + font.getWidth(label), y + 7, TEXT_DIM)
            context.drawTextWithShadow(font, "›", x + width - 12 + (2 * glow).roundToInt(), y + 7,
                lerpColor(glow, TEXT_DIM, ACCENT_A))
        }

        override fun appendClickableNarrations(builder: NarrationMessageBuilder) {
            appendDefaultNarrations(builder)
        }
    }
}
