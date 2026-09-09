package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import ai.hermes.mobile.runtime.bridge.protocol.CanonicalJson
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class NotificationInput(
    val systemKey: String,
    val sourcePackage: String,
    val postedAtEpochMillis: Long,
    val title: String?,
    val text: String?,
    val subText: String?,
    val category: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
)

internal data class NotificationQuery(
    val cursor: String?,
    val limit: Int,
    val sourcePackages: Set<String>,
)

internal data class NotificationCapture(
    val artifact: ArtifactReference,
    val redactions: List<String>,
)

internal enum class NotificationFailureReason(
    val retryDisposition: String,
    val recoverable: Boolean,
) {
    CURSOR_INVALID("REPLAN", true),
    CURSOR_EXPIRED("REPLAN", true),
    CAPTURE_FAILED("RETRY_SAME_ACTION", true),
}

internal class NotificationCaptureException(
    val reason: NotificationFailureReason,
) : IllegalStateException("notification capture failed: $reason")

internal interface NotificationCaptureSource {
    fun availability(): PhoneStateUnavailableReason?

    fun capture(query: NotificationQuery): NotificationCapture
}

internal fun interface NotificationClock {
    fun nowMillis(): Long
}

internal data class NotificationBatch(
    val payload: ByteArray,
    val redactions: List<String>,
)

/** Bounded memory-only active set plus ordered change cursor. */
internal class NotificationLedger(
    secretKey: ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes),
    private val clock: NotificationClock = NotificationClock { System.currentTimeMillis() },
    private val sessionId: String = UUID.randomUUID().toString(),
) {
    private data class Snapshot(
        val notificationId: String,
        val sourcePackage: String,
        val postedAtEpochMillis: Long,
        val observedAtEpochMillis: Long,
        val title: String?,
        val text: String?,
        val subText: String?,
        val category: String?,
        val ongoing: Boolean,
        val clearable: Boolean,
        val digest: ByteArray,
    )

    private data class Change(
        val sequence: Long,
        val kind: String,
        val notificationId: String,
        val sourcePackage: String,
        val observedAtEpochMillis: Long,
        val snapshot: Snapshot?,
    )

    private val secretKey = secretKey.copyOf()
    private val active = linkedMapOf<String, Snapshot>()
    private val changes = ArrayDeque<Change>()
    private var sequence = 0L
    private var activeOverflowed = false

    init {
        require(this.secretKey.size >= KEY_BYTES) { "notification key must be at least 256-bit" }
        require(OPAQUE_SESSION.matches(sessionId)) { "notification session id is invalid" }
    }

    @Synchronized
    fun replaceActive(inputs: List<NotificationInput>) {
        val eligible =
            inputs.asSequence()
                .filter { validPackage(it.sourcePackage) && validSystemKey(it.systemKey) }
                .take(MAX_ACTIVE_NOTIFICATIONS + 1)
                .toList()
        activeOverflowed = eligible.size > MAX_ACTIVE_NOTIFICATIONS
        val bounded = eligible.take(MAX_ACTIVE_NOTIFICATIONS)
        val incomingIds = bounded.mapTo(linkedSetOf()) { notificationId(it) }
        active.keys.filter { it !in incomingIds }.toList().forEach(::removeById)
        bounded.forEach(::post)
    }

    @Synchronized
    fun post(input: NotificationInput): Boolean {
        val normalized = normalize(input) ?: return false
        val previous = active[normalized.notificationId]
        if (previous != null && MessageDigest.isEqual(previous.digest, normalized.digest)) {
            normalized.digest.fill(0)
            return false
        }
        previous?.digest?.fill(0)
        if (previous == null && active.size >= MAX_ACTIVE_NOTIFICATIONS) {
            activeOverflowed = true
            removeById(active.entries.first().key)
        }
        active[normalized.notificationId] = normalized
        appendChange(if (previous == null) "POSTED" else "UPDATED", normalized)
        return true
    }

    @Synchronized
    fun remove(systemKey: String, sourcePackage: String): Boolean {
        if (!validPackage(sourcePackage) || !validSystemKey(systemKey)) return false
        return removeById(notificationId(systemKey, sourcePackage))
    }

    @Synchronized
    fun query(query: NotificationQuery): NotificationBatch {
        require(query.limit in 1..MAX_QUERY_LIMIT) { "notification limit is invalid" }
        require(query.sourcePackages.size <= MAX_SOURCE_PACKAGES) {
            "too many notification source packages"
        }
        require(query.sourcePackages.all(::validPackage)) { "notification source package is invalid" }
        val document =
            if (query.cursor == null) {
                snapshotDocument(query)
            } else {
                changesDocument(query, parseCursor(query.cursor))
            }
        return NotificationBatch(
            payload = CanonicalJson.encode(document),
            redactions = listOf(FIELDS_MINIMIZED),
        )
    }

    @Synchronized
    fun clear() {
        active.values.forEach { it.digest.fill(0) }
        changes.mapNotNull { it.snapshot }.forEach { it.digest.fill(0) }
        active.clear()
        changes.clear()
        activeOverflowed = false
    }

    private fun snapshotDocument(query: NotificationQuery): Map<String, Any?> {
        val matches =
            active.values
                .asSequence()
                .filter { query.sourcePackages.isEmpty() || it.sourcePackage in query.sourcePackages }
                .sortedByDescending { it.postedAtEpochMillis }
                .toList()
        return linkedMapOf(
            "schema_version" to 1,
            "mode" to "ACTIVE_SNAPSHOT",
            "cursor" to cursor(sequence),
            "truncated" to (activeOverflowed || matches.size > query.limit),
            "records" to matches.take(query.limit).map(::snapshotValue),
        )
    }

    private fun changesDocument(query: NotificationQuery, afterSequence: Long): Map<String, Any?> {
        val earliest = changes.firstOrNull()?.sequence ?: (sequence + 1)
        if (afterSequence < earliest - 1L) {
            throw NotificationCaptureException(NotificationFailureReason.CURSOR_EXPIRED)
        }
        if (afterSequence > sequence) {
            throw NotificationCaptureException(NotificationFailureReason.CURSOR_INVALID)
        }
        val matches =
            changes.filter {
                it.sequence > afterSequence &&
                    (query.sourcePackages.isEmpty() || it.sourcePackage in query.sourcePackages)
            }
        val selected = matches.take(query.limit)
        val nextSequence = selected.lastOrNull()?.sequence ?: sequence
        return linkedMapOf(
            "schema_version" to 1,
            "mode" to "CHANGES",
            "cursor" to cursor(nextSequence),
            "has_more" to (matches.size > selected.size),
            "records" to selected.map(::changeValue),
        )
    }

    private fun parseCursor(value: String): Long {
        val prefix = "$CURSOR_PREFIX:$sessionId:"
        if (!value.startsWith(prefix)) {
            throw NotificationCaptureException(NotificationFailureReason.CURSOR_INVALID)
        }
        return value.removePrefix(prefix).toLongOrNull()?.takeIf { it >= 0 }
            ?: throw NotificationCaptureException(NotificationFailureReason.CURSOR_INVALID)
    }

    private fun cursor(value: Long): String = "$CURSOR_PREFIX:$sessionId:$value"

    private fun removeById(notificationId: String): Boolean {
        val removed = active.remove(notificationId) ?: return false
        removed.digest.fill(0)
        sequence += 1
        append(
            Change(
                sequence,
                "REMOVED",
                notificationId,
                removed.sourcePackage,
                clock.nowMillis().coerceIn(0, MAX_SAFE_INTEGER),
                null,
            ),
        )
        return true
    }

    private fun appendChange(kind: String, snapshot: Snapshot) {
        sequence += 1
        append(
            Change(
                sequence,
                kind,
                snapshot.notificationId,
                snapshot.sourcePackage,
                snapshot.observedAtEpochMillis,
                snapshot,
            ),
        )
    }

    private fun append(change: Change) {
        changes.addLast(change)
        while (changes.size > MAX_CHANGES) changes.removeFirst()
    }

    private fun normalize(input: NotificationInput): Snapshot? {
        if (!validPackage(input.sourcePackage) || !validSystemKey(input.systemKey)) return null
        val notificationId = notificationId(input)
        val observedAt = clock.nowMillis().coerceIn(0, MAX_SAFE_INTEGER)
        val postedAt = input.postedAtEpochMillis.coerceIn(0, MAX_SAFE_INTEGER)
        val fields =
            linkedMapOf<String, Any?>(
                "notification_id" to notificationId,
                "source_package" to input.sourcePackage,
                "posted_at_epoch_ms" to postedAt,
                "title" to boundedText(input.title),
                "text" to boundedText(input.text),
                "sub_text" to boundedText(input.subText),
                "category" to boundedMetadata(input.category),
                "ongoing" to input.ongoing,
                "clearable" to input.clearable,
            )
        val material = CanonicalJson.encode(fields)
        val digest = try { hmac(material) } finally { material.fill(0) }
        return Snapshot(
            notificationId,
            input.sourcePackage,
            postedAt,
            observedAt,
            fields["title"] as String?,
            fields["text"] as String?,
            fields["sub_text"] as String?,
            fields["category"] as String?,
            input.ongoing,
            input.clearable,
            digest,
        )
    }

    private fun notificationId(input: NotificationInput): String =
        notificationId(input.systemKey, input.sourcePackage)

    private fun notificationId(systemKey: String, sourcePackage: String): String {
        val material = "$sourcePackage\u0000$systemKey".toByteArray(Charsets.UTF_8)
        val digestBytes = try { hmac(material) } finally { material.fill(0) }
        val digest = try { digestBytes.toHex() } finally { digestBytes.fill(0) }
        return "notification:$digest"
    }

    private fun snapshotValue(snapshot: Snapshot): Map<String, Any?> =
        linkedMapOf(
            "kind" to "ACTIVE",
            "notification_id" to snapshot.notificationId,
            "source_package" to snapshot.sourcePackage,
            "posted_at_epoch_ms" to snapshot.postedAtEpochMillis,
            "observed_at_epoch_ms" to snapshot.observedAtEpochMillis,
            "title" to snapshot.title,
            "text" to snapshot.text,
            "sub_text" to snapshot.subText,
            "category" to snapshot.category,
            "ongoing" to snapshot.ongoing,
            "clearable" to snapshot.clearable,
        )

    private fun changeValue(change: Change): Map<String, Any?> =
        change.snapshot?.let(::snapshotValue)?.plus("kind" to change.kind)
            ?: linkedMapOf(
                "kind" to change.kind,
                "notification_id" to change.notificationId,
                "source_package" to change.sourcePackage,
                "observed_at_epoch_ms" to change.observedAtEpochMillis,
            )

    private fun boundedText(value: String?): String? = bounded(value, MAX_TEXT_CHARS)

    private fun boundedMetadata(value: String?): String? = bounded(value, MAX_METADATA_CHARS)

    private fun bounded(value: String?, maximumChars: Int): String? =
        value
            ?.map { if (it.isISOControl()) ' ' else it }
            ?.joinToString("")
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.let { unicodePrefix(it, maximumChars) }
            ?.takeIf(String::isNotEmpty)

    private fun unicodePrefix(value: String, maximumCodePoints: Int): String {
        if (value.codePointCount(0, value.length) <= maximumCodePoints) return value
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints))
    }

    private fun hmac(material: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(secretKey, HMAC_ALGORITHM))
            doFinal(material)
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun validPackage(value: String): Boolean =
        value.length <= 255 && PACKAGE_NAME.matches(value)

    private fun validSystemKey(value: String): Boolean = value.length in 1..MAX_SYSTEM_KEY_CHARS

    private companion object {
        const val KEY_BYTES = 32
        const val MAX_ACTIVE_NOTIFICATIONS = 100
        const val MAX_CHANGES = 256
        const val MAX_QUERY_LIMIT = 100
        const val MAX_SOURCE_PACKAGES = 20
        // Three fields x 512 worst-case Unicode code points x 100 records
        // remains below the protocol's 1 MiB canonical JSON ceiling.
        const val MAX_TEXT_CHARS = 512
        const val MAX_METADATA_CHARS = 256
        const val MAX_SYSTEM_KEY_CHARS = 4_096
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        const val CURSOR_PREFIX = "notifications"
        const val FIELDS_MINIMIZED = "NOTIFICATION_FIELDS_MINIMIZED"
        const val HMAC_ALGORITHM = "HmacSHA256"
        val OPAQUE_SESSION = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        val WHITESPACE = Regex("\\s+")
    }
}
