package ai.hermes.mobile.runtime.bridge

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = BootstrapStatusProvider.current()
        val spacing = (24 * resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(spacing, spacing, spacing, spacing)
            }

        layout.addView(
            TextView(this).apply {
                text = getString(R.string.bootstrap_title, status.phase)
                textSize = 24f
                gravity = Gravity.CENTER
            },
        )
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.bootstrap_status, status.summary)
                textSize = 16f
                gravity = Gravity.CENTER
            },
        )
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.capabilities_disabled)
                textSize = 14f
                gravity = Gravity.CENTER
            },
        )

        setContentView(layout)
    }
}

