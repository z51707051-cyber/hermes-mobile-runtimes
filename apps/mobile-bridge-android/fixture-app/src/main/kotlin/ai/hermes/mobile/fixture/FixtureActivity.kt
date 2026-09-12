package ai.hermes.mobile.fixture

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Deterministic, synthetic UI states used only by the emulator contract lane. */
@SuppressLint("SetTextI18n")
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_SCENARIO)) {
            SCENARIO_SLOW -> slowPage()
            SCENARIO_DIALOG -> delayedDialog()
            SCENARIO_KEYBOARD -> keyboardPage()
            SCENARIO_UI_CHANGE -> changingPage()
            else -> setContentView(label("Fixture ready"))
        }
    }

    private fun slowPage() {
        val status = label("Loading synthetic page")
        setContentView(status)
        status.postDelayed({ status.text = "Slow page ready" }, SLOW_DELAY_MILLIS)
    }

    private fun delayedDialog() {
        setContentView(label("Dialog host ready"))
        window.decorView.postDelayed(
            {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle("Synthetic dialog")
                        .setMessage("Dialog confirmation required")
                        .setPositiveButton("Keep open", null)
                        .show()
                }
            },
            DIALOG_DELAY_MILLIS,
        )
    }

    private fun keyboardPage() {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }
        val status = label("Keyboard hidden")
        val input =
            EditText(this).apply {
                hint = "Synthetic message"
                setText("Draft content")
                contentDescription = "Synthetic message editor"
            }
        root.addView(status)
        root.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // On API 30 the decor can consume IME insets before the content root.
        // Observe at the decor, then preserve its normal inset dispatch.
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            status.text =
                if (insets.isVisible(WindowInsets.Type.ime())) {
                    "Keyboard visible"
                } else {
                    "Keyboard hidden"
                }
            view.onApplyWindowInsets(insets)
        }
        setContentView(root)
        input.requestFocus()
        input.postDelayed(
            {
                getSystemService(Context.INPUT_METHOD_SERVICE)
                    .let { it as InputMethodManager }
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            },
            KEYBOARD_DELAY_MILLIS,
        )
    }

    private fun changingPage() {
        val target = label("Original element")
        setContentView(target)
        target.postDelayed(
            {
                target.text = "Replacement element"
                target.contentDescription = "Replacement semantic target"
            },
            UI_CHANGE_DELAY_MILLIS,
        )
    }

    private fun label(value: String): TextView =
        TextView(this).apply {
            text = value
            contentDescription = value
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val SCENARIO_SLOW = "slow"
        const val SCENARIO_DIALOG = "dialog"
        const val SCENARIO_KEYBOARD = "keyboard"
        const val SCENARIO_UI_CHANGE = "ui_change"
        private const val SLOW_DELAY_MILLIS = 3_000L
        private const val DIALOG_DELAY_MILLIS = 2_000L
        private const val KEYBOARD_DELAY_MILLIS = 500L
        private const val UI_CHANGE_DELAY_MILLIS = 2_000L
    }
}
