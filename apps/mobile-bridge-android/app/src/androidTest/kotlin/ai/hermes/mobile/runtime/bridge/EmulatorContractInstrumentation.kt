package ai.hermes.mobile.runtime.bridge

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec
import ai.hermes.mobile.runtime.bridge.runtime.AndroidPolicyEnforcementPoint
import ai.hermes.mobile.runtime.bridge.runtime.BridgeRuntime
import ai.hermes.mobile.runtime.bridge.runtime.PepDecision
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateStore
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Black-box emulator contract runner with no dependency on a test framework. */
class EmulatorContractInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private val requestSequence = AtomicInteger()
    private val router =
        BridgeRuntime.router(
            AndroidPolicyEnforcementPoint { PepDecision.allow() },
        )

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val mode = arguments.getString(ARG_MODE, MODE_GRANTED)
        try {
            when (mode) {
                MODE_UNGRANTED -> verifyAccessibilityUnavailable()
                MODE_GRANTED -> verifyGrantedScenarios()
                else -> fail("unsupported emulator contract mode")
            }
            finishWith(Activity.RESULT_OK, "HMR_CONTRACT_STATUS=PASSED mode=$mode")
        } catch (failure: Throwable) {
            val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }
            finishWith(
                Activity.RESULT_CANCELED,
                "HMR_CONTRACT_STATUS=FAILED mode=$mode\n$trace",
            )
        }
    }

    private fun verifyAccessibilityUnavailable() {
        val capabilities = BridgeRuntime.availableCapabilities().toSet()
        check("phone.wait" in capabilities, "timer wait must remain available")
        val unexpectedlyAvailable = ACCESSIBILITY_CAPABILITIES.intersect(capabilities)
        check(unexpectedlyAvailable.isEmpty(), "Accessibility capability advertised without grant")
    }

    private fun verifyGrantedScenarios() {
        // Starting instrumentation force-stops the target process, including its
        // AccessibilityService. Rebind only after that restart, in this test APK.
        val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val service = "ai.hermes.mobile.runtime/ai.hermes.mobile.runtime.bridge.accessibility.CurrentAppAccessibilityService"
        for (command in listOf(
            "settings delete secure enabled_accessibility_services",
            "settings put secure enabled_accessibility_services $service",
            "settings put secure accessibility_enabled 1",
        )) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
                it.readBytes()
            }
            // Let the system consume each settings transition before the next.
            SystemClock.sleep(1_000)
        }
        val bindDeadline = SystemClock.elapsedRealtime() + 20_000
        while (PhoneStateStore.availability(5_000) == PhoneStateUnavailableReason.SERVICE_DISCONNECTED) {
            check(SystemClock.elapsedRealtime() < bindDeadline, "Accessibility service did not bind")
            SystemClock.sleep(100)
        }
        launchScenario(SCENARIO_SLOW)
        awaitForeground()
        checkSuccess(waitForText("Loading synthetic page", 5_000), "slow page baseline")
        targetContext.startActivity(
            Intent("ai.hermes.mobile.fixture.ARM_SLOW").apply {
                component = ComponentName(FIXTURE_PACKAGE, FIXTURE_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        val slow = waitForText("Slow page ready", timeoutMillis = 5_000)
        checkSuccess(slow, "slow page")
        check((slow["duration"] as Number).toLong() >= MINIMUM_SLOW_WAIT_MILLIS, "slow page was not re-observed")

        launchScenario(SCENARIO_DIALOG)
        awaitForeground()
        checkSuccess(waitForText("Dialog confirmation required", 5_000), "dialog appearance")
        val back = execute("phone.back", emptyMap(), risk = "L1", verification = textAbsent("Dialog confirmation required"))
        checkSuccess(back, "dialog back navigation")

        launchScenario(SCENARIO_KEYBOARD)
        awaitForeground()
        checkSuccess(waitForText("Keyboard visible", 5_000), "keyboard visibility")

        launchScenario(SCENARIO_UI_CHANGE)
        awaitForeground()
        val changed =
            execute(
                tool = "phone.wait",
                parameters =
                    mapOf(
                        "timeout_ms" to 5_000,
                        "condition" to mapOf("kind" to "STATE_CHANGED"),
                    ),
            )
        checkSuccess(changed, "semantic UI change")
        check(verification(changed)["status"] == "PASSED", "UI change was not verified")
    }

    private fun launchScenario(scenario: String) {
        val intent =
            Intent().apply {
                component = ComponentName(FIXTURE_PACKAGE, FIXTURE_ACTIVITY)
                putExtra(EXTRA_SCENARIO, scenario)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        targetContext.startActivity(intent)
    }

    private fun awaitForeground() {
        var lastStatus = "no result"
        repeat(FOREGROUND_ATTEMPTS) {
            try {
                val result = execute("phone.current_app", emptyMap())
                val after = result["after_state"] as? Map<*, *>
                lastStatus = "status=${result["execution_status"]}, error=${result["error"]}"
                if (
                    result["execution_status"] == "SUCCEEDED" &&
                    after?.get("foreground_package") == FIXTURE_PACKAGE
                ) {
                    val screen = execute("phone.read_screen", emptyMap())
                    if (screen["execution_status"] == "SUCCEEDED") return
                    lastStatus = "screen status=${screen["execution_status"]}, error=${screen["error"]}"
                }
            } catch (failure: Exception) {
                lastStatus = "${failure.javaClass.simpleName}: ${failure.message}"
                // The system may deliver the first window event after the process starts.
            }
            SystemClock.sleep(FOREGROUND_POLL_MILLIS)
        }
        fail("fixture app did not become the observed foreground app: $lastStatus")
    }

    private fun waitForText(
        expected: String,
        timeoutMillis: Int,
    ): Map<String, Any?> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (true) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt()
            val result = execute(
                tool = "phone.wait",
                parameters = mapOf(
                    "timeout_ms" to remaining,
                    "condition" to mapOf("kind" to "TEXT_PRESENT", "expected" to expected),
                ),
            )
            val error = result["error"] as? Map<*, *>
            val details = error?.get("details") as? Map<*, *>
            // Only retry read-only observation when the provider explicitly asks
            // for it during a window transition. Keep one overall deadline.
            if (error?.get("retry_disposition") != "REOBSERVE" ||
                details?.get("reason") !in setOf("UI_WINDOW_MISMATCH", "ACTIVE_WINDOW_UNAVAILABLE", "NO_WINDOW_STATE") ||
                SystemClock.elapsedRealtime() >= deadline
            ) return result
            SystemClock.sleep(100)
        }
    }

    private fun execute(
        tool: String,
        parameters: Map<String, Any?>,
        risk: String = "L0",
        verification: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val now = Instant.now()
        val sequence = requestSequence.incrementAndGet()
        val requestId = "emulator-$sequence"
        val action =
            linkedMapOf<String, Any?>(
                "message_type" to "action.authorized",
                "protocol_version" to "0.1.1",
                "request_id" to requestId,
                "task_id" to "emulator-task",
                "span_id" to "emulator-span-$sequence",
                "device_id" to "emulator-device",
                "tool" to tool,
                "parameters" to parameters,
                "state_precondition" to null,
                "verification" to verification,
                "idempotency_key" to "emulator-idempotency-$sequence",
                "attempt" to 1,
                "requested_at" to now.toString(),
                "deadline" to now.plusSeconds(ACTION_BOUND_SECONDS).toString(),
                "effective_target" to null,
                "effective_risk" to risk,
                "policy_decision_id" to "emulator-decision-$sequence",
                "execution_authorization" to "emulator_authorization_token",
                "authorization_algorithm" to "ES256",
                "authorization_key_id" to "emulator-key",
                "broker_id" to "emulator-broker",
                "session_id" to "emulator-session",
                "sequence" to sequence,
                "nonce" to "emulator_nonce_value_${sequence.toString().padStart(4, '0')}",
                "issued_at" to now.toString(),
                "expires_at" to now.plusSeconds(ACTION_BOUND_SECONDS).toString(),
            )
        action["action_digest"] = CanonicalJson.actionDigest(action)
        return ProtocolCodec.decode(
            router.routeAuthorized(ProtocolCodec.encode(action)),
        )
    }

    private fun checkSuccess(
        result: Map<String, Any?>,
        scenario: String,
    ) {
        check(result["execution_status"] == "SUCCEEDED", "$scenario execution failed: ${result["error"]}")
        check(verification(result)["status"] in setOf("PASSED", "NOT_APPLICABLE"), "$scenario verification failed: ${verification(result)}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun verification(result: Map<String, Any?>): Map<String, Any?> =
        result.getValue("verification") as Map<String, Any?>

    private fun textAbsent(expected: String): Map<String, Any?> =
        mapOf("condition" to "TEXT_ABSENT", "expected" to expected)

    private fun check(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) fail(message)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun finishWith(
        resultCode: Int,
        output: String,
    ) {
        finish(
            resultCode,
            Bundle().apply {
                putString("stream", "$output\n")
            },
        )
    }

    private companion object {
        const val ARG_MODE = "mode"
        const val MODE_UNGRANTED = "ungranted"
        const val MODE_GRANTED = "granted"
        const val FIXTURE_PACKAGE = "ai.hermes.mobile.fixture"
        const val FIXTURE_ACTIVITY = "ai.hermes.mobile.fixture.FixtureActivity"
        const val EXTRA_SCENARIO = "scenario"
        const val SCENARIO_SLOW = "slow"
        const val SCENARIO_DIALOG = "dialog"
        const val SCENARIO_KEYBOARD = "keyboard"
        const val SCENARIO_UI_CHANGE = "ui_change"
        const val ACTION_BOUND_SECONDS = 10L
        const val FOREGROUND_ATTEMPTS = 50
        const val FOREGROUND_POLL_MILLIS = 100L
        const val MINIMUM_SLOW_WAIT_MILLIS = 500L
        val ACCESSIBILITY_CAPABILITIES =
            setOf(
                "phone.current_app",
                "phone.read_screen",
                "phone.screenshot",
                "phone.tap",
                "phone.long_press",
                "phone.type",
                "phone.swipe",
                "phone.back",
                "phone.home",
                "phone.open_app",
            )
    }
}
