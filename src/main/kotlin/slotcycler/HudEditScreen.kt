package slotcycler

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import slotcycler.Ui.ACCENT_A
import slotcycler.Ui.CHIP_BG
import slotcycler.Ui.EDGE
import slotcycler.Ui.TEXT
import slotcycler.Ui.TEXT_DIM
import slotcycler.Ui.roundRect

class HudEditScreen(private val parent: Screen?) : Screen(Text.literal("HUD layout")) {

    private val samples = Qol.Kind.entries.associateWith { Qol.sample(it) }
    private var dragging: Qol.Box? = null
    private var grabX = 0.0
    private var grabY = 0.0

    override fun init() {
        val mid = width / 2
        addDrawableChild(Ui.Button(mid - 74, height / 2 + 6, 70, 16, "Reset", false) { Settings.current.resetHud() })
        addDrawableChild(Ui.Button(mid + 4, height / 2 + 6, 70, 16, "Done", true) { close() })
    }

    override fun close() {
        Settings.current.save()
        client?.setScreen(parent)
    }

    override fun shouldPause() = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0x70000000)
        val mid = width / 2
        context.roundRect(mid - 96, height / 2 - 30, mid + 96, height / 2 + 28, CHIP_BG)
        context.drawCenteredTextWithShadow(textRenderer, "HUD LAYOUT", mid, height / 2 - 24, TEXT)
        context.drawCenteredTextWithShadow(textRenderer, "Drag each element into place", mid, height / 2 - 11, TEXT_DIM)

        for (box in Qol.boxes) {
            Qol.layout(box, samples.getValue(box.kind), width, height)
            val hot = box === dragging || (dragging == null && inside(box, mouseX.toDouble(), mouseY.toDouble()))
            context.drawStrokedRectangle(box.x - 2, box.y - 2, box.w + 4, box.h + 4, if (hot) ACCENT_A else EDGE)
            Qol.draw(context, box)
            if (hot) {
                val labelY = if (box.y > 14) box.y - 12 else box.y + box.h + 4
                val labelX = (box.x).coerceAtMost(width - 4 - textRenderer.getWidth(box.kind.label))
                context.drawTextWithShadow(textRenderer, box.kind.label, labelX, labelY, ACCENT_A)
            }
        }
    }

    private fun inside(box: Qol.Box, x: Double, y: Double) =
        x >= box.x - 2 && x < box.x + box.w + 2 && y >= box.y - 2 && y < box.y + box.h + 2

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (super.mouseClicked(click, doubled)) return true
        val box = Qol.boxes.lastOrNull { inside(it, click.x(), click.y()) } ?: return false
        dragging = box
        grabX = click.x() - box.x
        grabY = click.y() - box.y
        return true
    }

    override fun mouseDragged(click: Click, offsetX: Double, offsetY: Double): Boolean {
        val box = dragging ?: return super.mouseDragged(click, offsetX, offsetY)
        val pos = Qol.position(box.kind)
        val spanX = (width - 8 - box.w).coerceAtLeast(1)
        val spanY = (height - 8 - box.h).coerceAtLeast(1)
        pos.x = ((click.x() - grabX - 4) / spanX).toFloat().coerceIn(0f, 1f)
        pos.y = ((click.y() - grabY - 4) / spanY).toFloat().coerceIn(0f, 1f)
        return true
    }

    override fun mouseReleased(click: Click): Boolean {
        dragging = null
        return super.mouseReleased(click)
    }
}
