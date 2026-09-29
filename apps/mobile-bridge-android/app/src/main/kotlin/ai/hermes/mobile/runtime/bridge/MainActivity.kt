package ai.hermes.mobile.runtime.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ai.hermes.mobile.runtime.bridge.model.ModelConfigStore
import ai.hermes.mobile.runtime.bridge.model.ModelEndpointValidator

class MainActivity : Activity() {
    private val modelConfigStore by lazy { ModelConfigStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        renderUi()
    }

    private fun renderUi() {
        val status = BootstrapStatusProvider.current()
        val modelStatus = modelConfigStore.status()
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
                text = getString(R.string.runtime_integration_status)
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
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.model_settings_title)
                textSize = 20f
                setPadding(0, spacing, 0, 0)
            },
        )
        layout.addView(
            TextView(this).apply {
                text =
                    if (modelStatus.hasApiKey) {
                        getString(R.string.model_key_configured)
                    } else {
                        getString(R.string.model_key_missing)
                    }
            },
        )
        val baseUrl =
            EditText(this).apply {
                hint = getString(R.string.model_base_url_hint)
                setText(modelStatus.endpoint?.baseUrl ?: DEFAULT_BASE_URL)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        val model =
            EditText(this).apply {
                hint = getString(R.string.model_name_hint)
                setText(modelStatus.endpoint?.model.orEmpty())
                inputType = InputType.TYPE_CLASS_TEXT
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        val apiKey =
            EditText(this).apply {
                hint =
                    if (modelStatus.hasApiKey) {
                        getString(R.string.model_api_key_keep_hint)
                    } else {
                        getString(R.string.model_api_key_hint)
                    }
                inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        layout.addView(baseUrl)
        layout.addView(model)
        layout.addView(apiKey)
        layout.addView(
            Button(this).apply {
                setText(R.string.save_model_settings)
                setOnClickListener {
                    try {
                        val endpoint =
                            ModelEndpointValidator.validate(
                                baseUrl.text.toString(),
                                model.text.toString(),
                            )
                        val key =
                            apiKey.text.toString().takeIf { it.isNotBlank() }?.toCharArray()
                        modelConfigStore.save(endpoint, key)
                        apiKey.text?.clear()
                        Toast.makeText(
                            this@MainActivity,
                            R.string.model_settings_saved,
                            Toast.LENGTH_SHORT,
                        ).show()
                        renderUi()
                    } catch (_: IllegalArgumentException) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.model_settings_invalid,
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (_: IllegalStateException) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.model_settings_store_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
        layout.addView(
            Button(this).apply {
                setText(R.string.clear_model_api_key)
                isEnabled = modelStatus.hasApiKey
                setOnClickListener {
                    try {
                        modelConfigStore.clearApiKey()
                        renderUi()
                    } catch (_: IllegalStateException) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.model_settings_store_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
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

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
