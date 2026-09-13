package ai.hermes.mobile.runtime.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()

        val status = BootstrapStatusProvider.current()
        val spacing = (24 * resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(spacing, spacing, spacing, spacing)
            }

        layout.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                gravity = Gravity.CENTER
            },
        )
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.trial_connection_pending)
                textSize = 16f
                gravity = Gravity.CENTER
            },
        )
        layout.addView(
            TextView(this).apply {
                text =
                    if (status.enabledCapabilities.isEmpty()) {
                        getString(R.string.capabilities_disabled)
                    } else {
                        getString(
                            R.string.capabilities_enabled,
                            status.enabledCapabilities.joinToString(),
                        )
                    }
                textSize = 14f
                gravity = Gravity.CENTER
            },
        )

        layout.addView(
            TextView(this).apply {
                text = getString(R.string.trial_permissions_help)
                textSize = 16f
                setPadding(0, spacing, 0, spacing)
            },
        )
        addSettingsButton(layout, R.string.open_accessibility_settings, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        addSettingsButton(layout, R.string.open_notification_settings, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun addSettingsButton(layout: LinearLayout, label: Int, action: String) {
        val intent = Intent(action)
        layout.addView(
            Button(this).apply {
                setText(label)
                isEnabled = intent.resolveActivity(packageManager) != null
                setOnClickListener { startActivity(intent) }
            },
        )
    }
}
