package slotcycler

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Util
import slotcycler.Ui.ACCENT_MID
import slotcycler.Ui.EDGE
import slotcycler.Ui.TEXT
import slotcycler.Ui.TEXT_DIM
import slotcycler.Ui.TITLE
import slotcycler.Ui.alpha
import slotcycler.Ui.panel

class HubScreen : Screen(Text.literal("Slot Cycler")) {

    private companion object {
        const val PANEL_W = 392
        const val PANEL_H = 244
        const val COL_W = 180
        const val PITCH = 22
        const val TOP = 44
    }

    private val s get() = Settings.current
    private val openedAt = Util.getMeasuringTimeMs()
    private val headers = ArrayList<Triple<String, Int, Int>>()
    private var px = 0
    private var py = 0

    override fun init() {
        px = (width - PANEL_W) / 2
        py = (height - PANEL_H) / 2
        val left = px + 12
        val right = px + PANEL_W - 12 - COL_W
        headers.clear()

        var x = left
        var y = py + TOP
        fun header(text: String) {
            headers += Triple(text, x, y)
            y += 12
        }
        fun toggle(label: String, hint: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            addDrawableChild(Ui.Toggle(x, y, COL_W, label, hint, get, set))
            y += PITCH
        }

        header("TRADING & CONTAINERS")
        toggle("Trade max button", "Adds a button to trading screens that repeats the selected trade until you run out.",
            { s.tradeMax }, { s.tradeMax = it })
        toggle("Container buttons", "Adds Take all, Deposit and Stack buttons to chests, barrels and shulker boxes.",
            { s.containerButtons }, { s.containerButtons = it })
        y += 4
        header("ALERTS & UTILITY")
        toggle("Durability alert", "Warns you when armour or a held tool drops below 10% durability.",
            { s.durabilityAlert }, { s.durabilityAlert = it })
        toggle("Death coordinates", "Prints where you died in chat.",
            { s.deathCoords }, { s.deathCoords = it })
        toggle("Fullbright", "Client-side night vision so dark areas stay visible.",
            { s.fullbright }, { s.fullbright = it })

        x = right
        y = py + TOP
        header("HUD")
        toggle("Armour durability", "Shows remaining durability for armour and held items in the bottom-right corner.",
            { s.armorHud }, { s.armorHud = it })
        toggle("Item tracker", "Counts the items listed below across your inventory.",
            { s.itemTracker }, { s.itemTracker = it })
        val field = TextFieldWidget(textRenderer, x + 1, y + 1, COL_W - 2, 16, Text.literal("Tracked items"))
        field.setMaxLength(96)
        field.text = s.trackedItems ?: ""
        field.setChangedListener { s.trackedItems = it }
        addDrawableChild(field)
        y += PITCH
        toggle("Effect timers", "Lists active potion effects with the time left.",
            { s.effectTimers }, { s.effectTimers = it })
        toggle("Info line", "Shows coordinates, facing and ping in the top-left corner.",
            { s.infoHud }, { s.infoHud = it })

        addDrawableChild(Ui.Link(left, py + 190, PANEL_W - 24, "Slot Cycler", "cycle, ghost check, surplus drop") {
            s.save()
            client?.setScreen(ConfigScreen(this))
        })
        addDrawableChild(Ui.Button(px + PANEL_W - 12 - 70, py + PANEL_H - 22, 70, 16, "Done", true) { close() })
    }

    override fun close() {
        s.save()
        super.close()
    }

    override fun shouldPause() = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val fade = ((Util.getMeasuringTimeMs() - openedAt) / 200f).coerceIn(0f, 1f)
        context.fillGradient(0, 0, width, height,
            alpha(0x05060F, (0xB8 * fade).toInt()), alpha(0x0C0D16, (0xD8 * fade).toInt()))
        context.panel(px, py, PANEL_W, PANEL_H, fade)

        val mid = px + PANEL_W / 2
        context.drawCenteredTextWithShadow(textRenderer, TITLE, mid, py + 10, TEXT)
        context.drawCenteredTextWithShadow(textRenderer, "Quality of life", mid, py + 24, TEXT_DIM)
        context.fill(px + 12, py + 37, px + PANEL_W - 12, py + 38, EDGE)
        for ((text, x, y) in headers) context.drawTextWithShadow(textRenderer, text, x + 1, y, ACCENT_MID)
        context.drawTextWithShadow(textRenderer, "Tracked items are comma separated, matched by name.",
            px + 12, py + PANEL_H - 18, TEXT_DIM)
    }
}
