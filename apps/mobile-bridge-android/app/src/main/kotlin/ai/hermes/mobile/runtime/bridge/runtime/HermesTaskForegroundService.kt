package ai.hermes.mobile.runtime.bridge.runtime

import android.Manifest
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import ai.hermes.mobile.runtime.bridge.HermesMobileApplication
import ai.hermes.mobile.runtime.bridge.MainActivity
import ai.hermes.mobile.runtime.bridge.R

class HermesTaskForegroundService : Service() {
    private lateinit var coordinator: HermesTaskCoordinator
    private var wakeLock: PowerManager.WakeLock? = null
    private var terminalStop = false
    private val stateListener: (HermesTaskSession) -> Unit = ::handleState

    override fun onCreate() {
        super.onCreate()
        coordinator = (application as HermesMobileApplication).taskCoordinator
        createNotificationChannel()
        acquireBoundedWakeLock()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(coordinator.currentState()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        coordinator.addListener(stateListener)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) coordinator.cancel()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @TargetApi(35)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        coordinator.cancel()
        terminalStop = true
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        coordinator.removeListener(stateListener)
        if (!terminalStop &&
            HermesTaskForegroundPolicy.requiresForegroundService(coordinator.currentState().phase)
        ) {
            coordinator.cancel()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun handleState(state: HermesTaskSession) {
        if (HermesTaskForegroundPolicy.requiresForegroundService(state.phase)) {
            if (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state))
            }
            return
        }
        terminalStop = true
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: HermesTaskSession): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopTask =
            PendingIntent.getService(
                this,
                1,
                stopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val status =
            if (state.phase == HermesTaskPhase.STOPPING) {
                getString(R.string.task_notification_stopping)
            } else {
                getString(R.string.task_notification_running)
            }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.task_notification_title))
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher),
                    getString(R.string.task_notification_stop),
                    stopTask,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.task_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.task_notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireBoundedWakeLock() {
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "hermes_active_task"
        private const val NOTIFICATION_ID = 0x484d52
        private const val WAKE_LOCK_TAG = "HermesMobileRuntime:active-task"
        private const val WAKE_LOCK_TIMEOUT_MS = 16 * 60 * 1000L
        private const val ACTION_START =
            "ai.hermes.mobile.runtime.bridge.action.START_HERMES_TASK"
        private const val ACTION_STOP =
            "ai.hermes.mobile.runtime.bridge.action.STOP_HERMES_TASK"

        fun startIntent(context: Context): Intent =
            Intent(context, HermesTaskForegroundService::class.java).setAction(ACTION_START)

        private fun stopIntent(context: Context): Intent =
            Intent(context, HermesTaskForegroundService::class.java).setAction(ACTION_STOP)
    }
}
