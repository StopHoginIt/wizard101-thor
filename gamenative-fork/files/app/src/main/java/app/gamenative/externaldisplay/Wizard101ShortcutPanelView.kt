package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import app.gamenative.R
import com.winlator.widget.TouchpadView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * Second-screen panel for the Wizard101 PC client: a touchpad on top and a grid of
 * shortcut buttons underneath. Every button sends exactly one of the game's default
 * key bindings (Data/GameData/InputBindings.xml) for as long as it is held; nothing
 * here reads game state or chains actions.
 */
class Wizard101ShortcutPanelView(
    context: Context,
    private val xServer: XServer,
    touchpadViewProvider: () -> TouchpadView?,
) : LinearLayout(context) {

    private sealed interface Send
    private data class Keys(val key: XKeycode, val modifiers: List<XKeycode> = emptyList()) : Send
    private data class Mouse(val button: Pointer.Button) : Send
    private object ToggleKeyboard : Send

    private data class Shortcut(val label: String, val send: Send)

    private val rows: List<List<Shortcut>> = listOf(
        listOf(
            Shortcut("Backpack", Keys(XKeycode.KEY_B)),
            Shortcut("Quests", Keys(XKeycode.KEY_Q)),
            Shortcut("Map", Keys(XKeycode.KEY_M)),
            Shortcut("Spells", Keys(XKeycode.KEY_P)),
            Shortcut("Stats", Keys(XKeycode.KEY_C)),
        ),
        listOf(
            Shortcut("Friends", Keys(XKeycode.KEY_F)),
            Shortcut("Pet", Keys(XKeycode.KEY_I)),
            Shortcut("Crafting", Keys(XKeycode.KEY_J)),
            Shortcut("Garden", Keys(XKeycode.KEY_G)),
            Shortcut("Housing", Keys(XKeycode.KEY_H)),
        ),
        listOf(
            Shortcut("Potion", Keys(XKeycode.KEY_O, listOf(XKeycode.KEY_CTRL_L))),
            Shortcut("Mark", Keys(XKeycode.KEY_NEXT)),
            Shortcut("Recall", Keys(XKeycode.KEY_PRIOR)),
            Shortcut("Home", Keys(XKeycode.KEY_HOME)),
            Shortcut("Hub", Keys(XKeycode.KEY_END)),
        ),
        listOf(
            Shortcut("Menu", Keys(XKeycode.KEY_ESC)),
            Shortcut("Realm", Keys(XKeycode.KEY_M, listOf(XKeycode.KEY_CTRL_L))),
            Shortcut("Chat", Keys(XKeycode.KEY_ENTER)),
            Shortcut("R-Click", Mouse(Pointer.Button.BUTTON_RIGHT)),
            Shortcut("Keys", ToggleKeyboard),
        ),
    )

    private val held = mutableSetOf<Shortcut>()

    private val keyBackgroundColor = ContextCompat.getColor(context, R.color.external_display_key_background)
    private val keyHighlightColor = ContextCompat.getColor(context, R.color.external_display_key_highlight_background)
    private val keyColor = ContextCompat.getColor(context, R.color.external_display_key_color)
    private val surfaceColor = ContextCompat.getColor(context, R.color.external_display_surface_background)
    private val panelColor = ContextCompat.getColor(context, R.color.external_display_keyboard_background)

    private val touchpad = TouchpadView(context, xServer, false).apply {
        setBackgroundColor(surfaceColor)
        touchpadViewProvider()?.let { primary -> setSimTouchScreen(primary.isSimTouchScreen) }
    }

    private val keyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
        visibility = View.GONE
    }

    private val shortcutGrid = LinearLayout(context).apply {
        orientation = VERTICAL
        setMotionEventSplittingEnabled(true)
        val padding = dp(6)
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(panelColor)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(surfaceColor)

        val touchArea = FrameLayout(context).apply {
            addView(
                touchpad,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        addView(touchArea, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        rows.forEach { addShortcutRow(it) }
        addView(shortcutGrid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(keyboardView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addShortcutRow(shortcuts: List<Shortcut>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val margin = dp(3)
        shortcuts.forEach { shortcut ->
            val button = Button(context).apply {
                isAllCaps = false
                text = shortcut.label
                setTextColor(keyColor)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                setPadding(0, 0, 0, 0)
                background = buttonBackground()
                layoutParams = LayoutParams(0, dp(52), 1f).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> press(shortcut)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(shortcut)
                    }
                    false
                }
            }
            row.addView(button)
        }
        shortcutGrid.addView(
            row,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun press(shortcut: Shortcut) {
        if (!held.add(shortcut)) return
        when (val send = shortcut.send) {
            is Keys -> {
                send.modifiers.forEach { xServer.injectKeyPress(it) }
                xServer.injectKeyPress(send.key)
            }
            is Mouse -> xServer.injectPointerButtonPress(send.button)
            ToggleKeyboard -> Unit
        }
    }

    private fun release(shortcut: Shortcut) {
        if (!held.remove(shortcut)) return
        when (val send = shortcut.send) {
            is Keys -> {
                xServer.injectKeyRelease(send.key)
                send.modifiers.asReversed().forEach { xServer.injectKeyRelease(it) }
            }
            is Mouse -> xServer.injectPointerButtonRelease(send.button)
            ToggleKeyboard -> toggleKeyboard()
        }
    }

    /** The keyboard takes the shortcut grid's place so the touchpad keeps its room. */
    private fun toggleKeyboard() {
        val show = keyboardView.visibility != View.VISIBLE
        keyboardView.visibility = if (show) View.VISIBLE else View.GONE
        // Keep the last row visible so the "Keys" button can switch back.
        for (i in 0 until shortcutGrid.childCount - 1) {
            shortcutGrid.getChildAt(i).visibility = if (show) View.GONE else View.VISIBLE
        }
    }

    private fun buttonBackground() = StateListDrawable().apply {
        fun shape(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(color)
        }
        addState(intArrayOf(android.R.attr.state_pressed), shape(keyHighlightColor))
        addState(intArrayOf(), shape(keyBackgroundColor))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        held.toList().forEach { release(it) }
        held.clear()
        super.onDetachedFromWindow()
    }
}
