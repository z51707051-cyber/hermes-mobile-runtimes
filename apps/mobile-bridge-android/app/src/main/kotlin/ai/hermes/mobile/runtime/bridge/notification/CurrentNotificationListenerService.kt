package ai.hermes.mobile.runtime.bridge.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.hermes.mobile.runtime.bridge.observer.NotificationInput

/** System-bound notification observer; Android notification objects never leave callbacks. */
class CurrentNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        val snapshot =
            try {
                activeNotifications.orEmpty().mapNotNull(::normalized)
            } catch (exc: SecurityException) {
                emptyList()
            }
        NotificationCaptureGateway.connect(this, snapshot)
    }

    override fun onListenerDisconnected() {
        NotificationCaptureGateway.disconnect(this)
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        notification?.let(::normalized)?.let(NotificationCaptureGateway::post)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        notification ?: return
        val systemKey = notification.key ?: return
        val sourcePackage = notification.packageName ?: return
        NotificationCaptureGateway.remove(systemKey, sourcePackage)
    }

    override fun onDestroy() {
        NotificationCaptureGateway.disconnect(this)
        super.onDestroy()
    }

    private fun normalized(value: StatusBarNotification): NotificationInput? {
        val packageName = value.packageName ?: return null
        val systemKey = value.key ?: return null
        val notification = value.notification ?: return null
        val extras = notification.extras ?: return null
        return NotificationInput(
            systemKey = systemKey,
            sourcePackage = packageName,
            postedAtEpochMillis = value.postTime,
            title = bounded(extras.getCharSequence(Notification.EXTRA_TITLE), MAX_CALLBACK_TEXT_CHARS),
            text = bounded(extras.getCharSequence(Notification.EXTRA_TEXT), MAX_CALLBACK_TEXT_CHARS),
            subText = bounded(extras.getCharSequence(Notification.EXTRA_SUB_TEXT), MAX_CALLBACK_TEXT_CHARS),
            category = bounded(notification.category, MAX_CALLBACK_METADATA_CHARS),
            ongoing = value.isOngoing,
            clearable = value.isClearable,
        )
    }

    private fun bounded(value: CharSequence?, maximumCodePoints: Int): String? {
        value ?: return null
        var end = 0
        var codePoints = 0
        while (end < value.length && codePoints < maximumCodePoints) {
            end +=
                if (
                    Character.isHighSurrogate(value[end]) &&
                    end + 1 < value.length &&
                    Character.isLowSurrogate(value[end + 1])
                ) {
                    2
                } else {
                    1
                }
            codePoints += 1
        }
        return value.subSequence(0, end).toString()
    }

    private companion object {
        const val MAX_CALLBACK_TEXT_CHARS = 512
        const val MAX_CALLBACK_METADATA_CHARS = 256
    }
}
