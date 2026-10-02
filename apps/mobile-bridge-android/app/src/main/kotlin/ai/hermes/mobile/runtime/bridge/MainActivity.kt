package ai.hermes.mobile.runtime.bridge

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
    private val attachmentSelectionStore by lazy {
        (application as HermesMobileApplication).attachmentSelectionStore
    }
    private lateinit var taskCoordinator: HermesTaskCoordinator
    private val taskStateListener: (HermesTaskSession) -> Unit = { state -> renderTaskState(state) }
    private lateinit var capabilityStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var conversation: TextView
    private lateinit var taskInput: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var attachmentStatus: TextView
    private lateinit var selectAttachmentButton: Button
    private lateinit var clearAttachmentButton: Button
    private lateinit var modelKeyStatus: TextView
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
        attachmentStatus = TextView(this)
        selectAttachmentButton =
            Button(this).apply {
                setText(R.string.select_attachment)
                setOnClickListener { selectAttachment() }
            }
        clearAttachmentButton =
            Button(this).apply {
                setText(R.string.clear_attachment)
                setOnClickListener {
                    attachmentSelectionStore.clear()
                    refreshAttachmentStatus()
                }
            }
        val attachmentButtons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    selectAttachmentButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    clearAttachmentButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        layout.addView(attachmentStatus)
        layout.addView(attachmentButtons)
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
                hint = gevÁo-¢Gß≤⁄Óù∆≠y“equestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ATTACHMENT_PICK_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri == null) {
            Toast.makeText(this, R.string.attachment_selection_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            attachmentSelectionStore.select(uri)
            refreshAttachmentStatus()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.attachment_selection_failed, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.attachment_selection_failed, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.attachment_selection_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshAttachmentStatus() {
        val selected = attachmentSelectionStore.current()
        attachmentStatus.text =
            if (selected == null) {
                getString(R.string.attachment_not_selected)
            } else {
                getString(
                    R.string.attachment_selected,
                    selected.displayName,
                    selected.mimeType,
                )
            }
        if (::clearAttachmentButton.isInitialized) {
            clearAttachmentButton.isEnabled =
                selected != null && taskCoordinator.currentState().phase !in
                setOf(HermesTaskPhase.RUNNING, HermesTaskPhase.STOPPING)
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
        const val ATTACHMENT_PICK_REQUEST = 1102
    }
}
