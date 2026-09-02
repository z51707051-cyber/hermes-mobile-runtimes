package ai.hermes.mobile.runtime.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateStore

/**
 * Least-authority HMR-105/HMR-106 observer.
 *
 * The service consumes window identity only. Its XML configuration forbids UI
 * hierarchy retrieval and gesture dispatch, and this class never reads an
 * event source node or text payload.
 */
class CurrentAppAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        PhoneStateStore.markConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        PhoneStateStore.recordWindow(
            packageName = event.packageName?.toString(),
            activityName = event.className?.toString(),
        )
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        PhoneStateStore.markDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        PhoneStateStore.markDisconnected()
        super.onDestroy()
    }
}
