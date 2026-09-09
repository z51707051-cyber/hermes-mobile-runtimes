package ai.hermes.mobile.runtime.bridge.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import ai.hermes.mobile.runtime.bridge.artifact.ArtifactWriteRequest
import ai.hermes.mobile.runtime.bridge.artifact.RuntimeArtifactStore
import ai.hermes.mobile.runtime.bridge.observer.DeviceStateCapture
import ai.hermes.mobile.runtime.bridge.observer.DeviceStateCaptureSource
import ai.hermes.mobile.runtime.bridge.observer.DeviceStateQuery
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson

/** Allowlisted read-only device projection. It never returns SSID, BSSID or nearby devices. */
internal object AndroidDeviceStateGateway : DeviceStateCaptureSource {
    private var applicationContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    @Synchronized
    override fun availability(): PhoneStateUnavailableReason? =
        if (applicationContext == null) PhoneStateUnavailableReason.SERVICE_DISCONNECTED else null

    override fun capture(query: DeviceStateQuery): DeviceStateCapture {
        val context = synchronized(this) { applicationContext }
            ?: throw PhoneStateUnavailableException(PhoneStateUnavailableReason.SERVICE_DISCONNECTED)
        require(query.fields.isNotEmpty() && query.fields.all { it in ALLOWED_FIELDS }) {
            "device state fields are invalid"
        }
        val battery by lazy { battery(context) }
        val network by lazy { network(context) }
        val values = linkedMapOf<String, Any?>()
        query.fields.sorted().forEach { field ->
            values[field] =
                when (field) {
                    "BATTERY" -> battery.first
                    "CHARGING" -> battery.second
                    "WIFI" -> network.wifiConnected
                    "BLUETOOTH" ->
                        mapOf(
                            "available" to false,
                            "enabled" to null,
                            "reason" to "BLUETOOTH_CONNECT_NOT_REQUESTED",
                        )
                    "NETWORK" -> network.protocolValue()
                    "SCREEN" ->
                        requireNotNull(context.getSystemService(PowerManager::class.java)) {
                            "power manager unavailable"
                        }.isInteractive
                    "LOCALE" ->
                        context.resources.configuration.locales
                            .takeUnless { it.isEmpty }
                            ?.get(0)
                            ?.toLanguageTag()
                            ?: "und"
                    else -> error("unreachable device state field")
                }
        }
        val payload =
            CanonicalJson.encode(
                mapOf(
                    "schema_version" to 1,
                    "captured_at_epoch_ms" to System.currentTimeMillis(),
                    "fields" to values,
                ),
            )
        val reference =
            try {
                RuntimeArtifactStore.put(
                    ArtifactWriteRequest(
                        mediaType = "application/vnd.hermes.device-state+json",
                        content = payload,
                        sensitivity = "D2",
                        redactionStatus = "REDACTED",
                        timeToLiveMillis = ARTIFACT_TTL_MILLIS,
                    ),
                )
            } finally {
                payload.fill(0)
            }
        return DeviceStateCapture(
            reference,
            if ("BLUETOOTH" in query.fields) listOf("BLUETOOTH_STATE_WITHHELD") else emptyList(),
        )
    }

    private fun battery(context: Context): Pair<Map<String, Any?>, Map<String, Any?>> {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(null, filter)
            }
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent =
            if (level >= 0 && scale > 0) {
                ((level.toLong() * 100L) / scale).coerceIn(0, 100).toInt()
            } else {
                null
            }
        val statusCode = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging =
            statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusCode == BatteryManager.BATTERY_STATUS_FULL
        return mapOf("percent" to percent) to
            mapOf(
                "charging" to charging,
                "source" to
                    when {
                        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
                        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
                        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "WIRELESS"
                        else -> "NONE"
                    },
            )
    }

    private fun network(context: Context): NetworkProjection {
        val manager =
            requireNotNull(context.getSystemService(ConnectivityManager::class.java)) {
                "connectivity manager unavailable"
            }
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        return NetworkProjection(
            connected = capabilities != null,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = manager.isActiveNetworkMetered,
            wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            transports =
                buildList {
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                },
        )
    }

    private data class NetworkProjection(
        val connected: Boolean,
        val validated: Boolean,
        val metered: Boolean,
        val wifiConnected: Boolean,
        val transports: List<String>,
    ) {
        fun protocolValue(): Map<String, Any?> =
            mapOf(
                "connected" to connected,
                "validated" to validated,
                "metered" to metered,
                "transports" to transports,
            )
    }

    private const val ARTIFACT_TTL_MILLIS = 300_000L
    private val ALLOWED_FIELDS =
        setOf("BATTERY", "CHARGING", "WIFI", "BLUETOOTH", "NETWORK", "SCREEN", "LOCALE")
}
