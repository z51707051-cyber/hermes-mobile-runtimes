package ai.hermes.mobile.runtime.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ai.hermes.mobile.runtime.bridge.model.ModelConfigStore
import ai.hermes.mobile.runtime.bridge.model.ModelEndpointValidator
import ai.hermes.mobile.runtime.bridge.runtime.HermesTaskCoordinator
import ai.hermes.mobile.runtime.bridge.runtime.HermesTaskFailure
import ai.hermes.mobile.runtime.bridge.runtime.HermesTaskPhase
import ai.hermes.mobile.runtime.bridge.runtime.HermesTaskSession

class MainActivity : Activity() {
    private val modelConfigStore by lazy { ModelConfigStore(applicationContext) }
    private lateinit var taskCoordinator: HermesTaskCoordinator
    private val taskStateListener: (HermesTaskSession) -> Unit = { state -> renderTaskState(state) }
    private lateinit var capabilityStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var conversation: TextView
    private lateinit var taskInput: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var modelKeyStatus: TextView
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskCoordinator = (application as HermesMobileApplication).taskCoordinator
        buildUi()
        refreshConfigurationStatus()
        renderTaskState(taskCoordinator.currentState())
    }

    override fun onStart() {
        super.onStart()
        taskCoordinator.addListener(taskStateListener)
    }

    override fun onStop() {
        taskCoordinator.removeListener(taskStateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::capabilityStatus.isInitialized) refreshConfigurationStatus()
    }

    private fun buildUi() {
        val spacing = (20 * resources.displayMetrics.density).toInt()
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
                textSize = 15f
                gravity = Gravity.CENTER
            },
        )
        runtimeStatus = TextView(this).apply { gravity = Gravity.CENTER }
        capabilityStatus = TextView(this).apply { gravity = Gravity.CENTER }
        layout.addView(runtimeStatus)
        layout.addView(capabilityStatus)

        layout.addView(sectionTitle(R.string.task_title, spacing))
        conversation =
            TextView(this).apply {
                minHeight = (160 * resources.displayMetrics.density).toInt()
                textSize = 16f
                setPadding(0, spacing / 2, 0, spacing / 2)
                setTextIsSelectable(true)
            }
        taskInput =
            EditText(this).apply {
                hint = getString(R.string.task_prompt_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 7
            }
        sendButton =
            Button(this).apply {
                setText(R.string.task_send)
                setOnClickListener { submitTaskWithNotificationPermission() }
            }
        stopButton =
            Button(this).apply {
                setText(R.string.task_stop)
                isEnabled = false
                setOnClickListener { taskCoordinator.cancel() }
            }
        val taskButtons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    sendButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    stopButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        layout.addView(conversation)
        layout.addView(taskInput)
        layout.addView(taskButtons)

        layout.addView(sectionTitle(R.string.model_settings_title, spacing))
        modelKeyStatus = TextView(this)
        baseUrlInput =
            EditText(this).apply {
                hint = getString(R.string.model_base_url_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        modelInput =
            EditText(this).apply {
                hint = getString(R.string.model_name_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        apiKeyInput =
            EditText(this).apply {
                hint = getString(R.string.model_api_key_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        layout.addView(modelKeyStatus)
        layout.addView(
            Button(this).apply {
                setText(R.string.use_openai_preset)
                setOnClickListener {
                    baseUrlInput.setText(OPENAI_BASE_URL)
                    modelInput.setText(OPENAI_MODEL)
                }
            },
        )
        layout.addView(
            Button(this).apply {
                setText(R.string.use_deepseek_preset)
                setOnClickListener {
                    baseUrlInput.setText(DEEPSEEK_BASE_URL)
                    modelInput.setText(DEEPSEEK_MODEL)
                }
            },
        )
        layout.addView(baseUrlInput)
        layout.addView(modelInput)
        layout.addView(apiKeyInput)
        layout.addView(
            Button(this).apply {
                setText(R.string.save_model_settings)
                setOnClickListener { saveModelSettings() }
            },
        )
        layout.addView(
            Button(this).apply {
                setText(R.string.clear_model_api_key)
                setOnClickListener { clearModelApiKey() }
            },
        )

        layout.addView(sectionTitle(R.string.permissions_title, spacing))
        layout.addView(TextView(this).apply { setText(R.string.trial_permissions_help) })
        addSettingsButton(layout, R.string.open_accessibility_settings, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        addSettingsButton(layout, R.string.open_notification_settings, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun sectionTitle(
        label: Int,
        spacing: Int,
    ): TextView =
        TextView(this).apply {
            setText(label)
            textSize = 20f
            setPadding(0, spacing, 0, spacing / 4)
        }

    private fun refreshConfigurationStatus() {
        val bootstrap = BootstrapStatusProvider.current()
        val modelStatus = modelConfigStore.status()
        runtimeStatus.setText(
            if (taskCoordinator.isRuntimeAvailable) {
                R.string.embedded_runtime_ready
            } else {
                R.string.embedded_runtime_missing
            },
        )
        capabilityStatus.text =
            if (bootstrap.enabledCapabilities.isEmpty()) {
                getString(R.string.capabilities_disabled)
            } else {
                getString(R.string.capabilities_enabled, bootstrap.enabledCapabilities.joinToString())
            }
        modelKeyStatus.setText(
            if (modelStatus.hasApiKey) {
                R.string.model_key_configured
            } else {
                R.string.model_key_missing
            },
        )
        baseUrlInput.setText(modelStatus.endpoint?.baseUrl ?: DEFAULT_BASE_URL)
        modelInput.setText(modelStatus.endpoint?.model ?: DEFAULT_MODEL)
        apiKeyInput.hint =
            getString(
                if (modelStatus.hasApiKey) {
                    R.string.model_api_key_keep_hint
                } else {
                    R.string.model_api_key_hint
                },
            )
    }

    private fun renderTaskState(state: HermesTaskSession) {
        val busy = state.phase == HermesTaskPhase.RUNNING || state.phase == HermesTaskPhase.STOPPING
        sendButton.isEnabled = !busy
        stopButton.isEnabled = state.phase == HermesTaskPhase.RUNNING
        conversation.text =
            when (state.phase) {
                HermesTaskPhase.IDLE -> getString(R.string.task_idle)
                HermesTaskPhase.RUNNING -> getString(R.string.task_running, state.prompt)
                HermesTaskPhase.STOPPING -> getString(R.string.task_stopping, state.prompt)
                HermesTaskPhase.COMPLETED ->
                    getString(R.string.task_completed, state.prompt, state.response)
                HermesTaskPhase.CANCELLED -> getString(R.string.task_cancelled, state.prompt)
                HermesTaskPhase.FAILED ->
                    getString(
                        when (state.failure) {
                            HermesTaskFailure.CONFIGURATION -> R.string.task_failed_configuration
                            HermesTaskFailure.RUNTIME_UNAVAILABLE -> R.string.task_failed_runtime
                            else -> R.string.task_failed_execution
                        },
                    )
            }
    }

    private fun saveModelSettings() {
        try {
            val endpoint =
                ModelEndpointValidator.validate(
                    baseUrlInput.text.toString(),
                    modelInput.text.toString(),
                )
            val key = apiKeyInput.text.toString().takeIf { it.isNotBlank() }?.toCharArray()
            modelConfigStore.save(endpoint, key)
            apiKeyInput.text?.clear()
            Toast.makeText(this, R.string.model_settings_saved, Toast.LENGTH_SHORT).show()
            refreshConfigurationStatus()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.model_settings_invalid, Toast.LENGTH_LONG).show()
        } catch (_: IllegalStateException) {
            Toast.makeText(this, R.string.model_settings_store_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearModelApiKey() {
        try {
            modelConfigStore.clearApiKey()
            refreshConfigurationStatus()
        } catch (_: IllegalStateException) {
            Toast.makeText(this, R.string.model_settings_store_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun submitTaskWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
            return
        }
        submitCurrentTask()
    }

    private fun submitCurrentTask() {
        if (taskCoordinator.submit(taskInput.text.toString())) {
            taskInput.text?.clear()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            submitCurrentTask()
        } else {
            Toast.makeText(this, R.string.task_notification_permission_required, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun addSettingsButton(
        layout: LinearLayout,
        label: Int,
        action: String,
    ) {
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
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val OPENAI_MODEL = "gpt-5"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val DEFAULT_BASE_URL = OPENAI_BASE_URL
        const val DEFAULT_MODEL = OPENAI_MODEL
        const val NOTIFICATION_PERMISSION_REQUEST = 1101
    }
}
