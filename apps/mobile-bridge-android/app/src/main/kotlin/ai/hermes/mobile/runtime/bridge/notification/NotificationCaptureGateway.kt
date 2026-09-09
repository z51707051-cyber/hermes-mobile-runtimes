package ai.hermes.mobile.runtime.bridge.notification

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactWriteRequest
import ai.hermes.mobile.runtime.bridge.artifact.RuntimeArtifactStore
import ai.hermes.mobile.runtime.bridge.observer.NotificationCapture
import ai.hermes.mobile.runtime.bridge.observer.NotificationCaptureException
import ai.hermes.mobile.runtime.bridge.observer.NotificationCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.NotificationFailureReason
import ai.hermes.mobile.runtime.bridge.observer.NotificationInput
import ai.hermes.mobile.runtime.bridge.observer.NotificationLedger
import ai.hermes.mobile.runtime.bridge.observer.NotificationQuery
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import java.lang.ref.WeakReference

internal object NotificationCaptureGateway : NotificationCaptureSource {
    private var ledger = NotificationLedger()
    private var service = WeakReference<CurrentNotificationListenerService>(null)

    @Synchronized
    fun connect(value: CurrentNotificationListenerService, active: List<NotificationInput>) {
        if (service.get() !== value) {
            ledger.clear()
            ledger = NotificationLedger()
        }
        service = WeakReference(value)
        ledger.replaceActive(active)
    }

    @Synchronized
    fun disconnect(value: CurrentNotificationListenerService) {
        if (service.get() === value) {
            service.clear()
            ledger.clear()
            ledger = NotificationLedger()
        }
    }

    @Synchronized
    fun post(value: NotificationInput) {
        if (service.get() != null) ledger.post(value)
    }

    @Synchronized
    fun remove(systemKey: String, sourcePackage: String) {
        if (service.get() != null) ledger.remove(systemKey, sourcePackage)
    }

    @Synchronized
    override fun availability(): PhoneStateUnavailableReason? =
        if (service.get() == null) PhoneStateUnavailableReason.SERVICE_DISCONNECTED else null

    override fun capture(query: NotificationQuery): NotificationCapture {
        val activeLedger = synchronized(this) { if (service.get() == null) null else ledger }
            ?: throw NotificationCaptureException(NotificationFailureReason.CAPTURE_FAILED)
        val batch = activeLedger.query(query)
        val reference =
            try {
                RuntimeArtifactStore.put(
                    ArtifactWriteRequest(
                        mediaType = "application/vnd.hermes.notifications+json",
                        content = batch.payload,
                        sensitivity = "D3",
                        redactionStatus = "REDACTED",
                        timeToLiveMillis = ARTIFACT_TTL_MILLIS,
                    ),
                )
            } catch (exc: NotificationCaptureException) {
                throw exc
            } catch (exc: Exception) {
                throw NotificationCaptureException(NotificationFailureReason.CAPTURE_FAILED)
            } finally {
                batch.payload.fill(0)
            }
        return NotificationCapture(reference, batch.redactions)
    }

    private const val ARTIFACT_TTL_MILLIS = 300_000L
}
