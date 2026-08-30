package ai.hermes.mobile.runtime.bridge.security.replay

import android.content.Context
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Crash-safe replay ledger stored in Android's no-backup directory.
 *
 * The app sandbox protects this availability/integrity state. It contains only
 * opaque session identifiers, nonce digests, sequence numbers and expiries; no
 * authorization credential or user content is persisted.
 */
internal class AtomicFileReplayStateStore(
    context: Context,
    relativePath: String = "security/replay-ledger-v1.bin",
) : ReplayStateStore {
    private val atomicFile = AtomicFile(File(context.noBackupFilesDir, relativePath))

    @Synchronized
    override fun load(): ReplaySnapshot {
        if (!atomicFile.baseFile.exists()) return ReplaySnapshot()
        return try {
            DataInputStream(BufferedInputStream(atomicFile.openRead())).use { input ->
                requireState(input.readInt() == MAGIC, "invalid replay ledger magic")
                requireState(input.readInt() == FORMAT_VERSION, "unsupported replay ledger version")
                val sessionCount = input.readInt()
                requireState(sessionCount in 0..MAX_PERSISTED_SESSIONS, "invalid session count")
                val sessions = LinkedHashMap<String, ReplaySessionState>(sessionCount)
                repeat(sessionCount) {
                    val sessionId = input.readUTF()
                    requireState(SESSION_ID.matches(sessionId), "invalid session id")
                    val highestSequence = input.readLong()
                    requireState(highestSequence > 0, "invalid highest sequence")
                    val expiresAtEpochMs = input.readLong()
                    requireState(expiresAtEpochMs > 0, "invalid session expiry")
                    val nonceCount = input.readInt()
                    requireState(nonceCount in 0..MAX_PERSISTED_NONCES, "invalid nonce count")
                    val nonces =
                        buildList(nonceCount) {
                            repeat(nonceCount) {
                                val digest = input.readUTF()
                                requireState(
                                    NONCE_DIGEST.matches(digest),
                                    "invalid nonce digest",
                                )
                                val retainUntilEpochMs = input.readLong()
                                requireState(
                                    retainUntilEpochMs == expiresAtEpochMs,
                                    "invalid nonce retention",
                                )
                                add(
                                    SeenNonce(
                                        digest = digest,
                                        retainUntilEpochMs = retainUntilEpochMs,
                                    ),
                                )
                            }
                        }
                    requireState(
                        nonces.map(SeenNonce::digest).toSet().size == nonces.size,
                        "duplicate nonce digest",
                    )
                    requireState(
                        sessions.put(
                            sessionId,
                            ReplaySessionState(highestSequence, expiresAtEpochMs, nonces),
                        ) == null,
                        "duplicate session id",
                    )
                }
                requireState(input.read() == -1, "trailing replay ledger data")
                ReplaySnapshot(sessions)
            }
        } catch (exc: ReplayStateException) {
            throw exc
        } catch (exc: EOFException) {
            throw ReplayStateException("truncated replay ledger", exc)
        } catch (exc: IOException) {
            throw ReplayStateException("cannot read replay ledger", exc)
        } catch (exc: RuntimeException) {
            throw ReplayStateException("cannot parse replay ledger", exc)
        }
    }

    @Synchronized
    override fun save(snapshot: ReplaySnapshot) {
        atomicFile.baseFile.parentFile?.mkdirs()
        val output =
            try {
                atomicFile.startWrite()
            } catch (exc: IOException) {
                throw ReplayStateException("cannot open replay ledger for writing", exc)
        }

        try {
            requireState(
                snapshot.sessions.size <= MAX_PERSISTED_SESSIONS,
                "too many sessions to persist",
            )
            val data = DataOutputStream(BufferedOutputStream(output))
            data.writeInt(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(snapshot.sessions.size)
            snapshot.sessions.toSortedMap().forEach { (sessionId, session) ->
                requireState(SESSION_ID.matches(sessionId), "invalid session id")
                requireState(session.highestSequence > 0, "invalid highest sequence")
                requireState(session.expiresAtEpochMs > 0, "invalid session expiry")
                requireState(
                    session.seenNonces.size <= MAX_PERSISTED_NONCES,
                    "too many nonces to persist",
                )
                requireState(
                    session.seenNonces.map(SeenNonce::digest).toSet().size ==
                        session.seenNonces.size,
                    "duplicate nonce digest",
                )
                data.writeUTF(sessionId)
                data.writeLong(session.highestSequence)
                data.writeLong(session.expiresAtEpochMs)
                data.writeInt(session.seenNonces.size)
                session.seenNonces.sortedBy(SeenNonce::digest).forEach { nonce ->
                    requireState(NONCE_DIGEST.matches(nonce.digest), "invalid nonce digest")
                    requireState(
                        nonce.retainUntilEpochMs == session.expiresAtEpochMs,
                        "invalid nonce retention",
                    )
                    data.writeUTF(nonce.digest)
                    data.writeLong(nonce.retainUntilEpochMs)
                }
            }
            data.flush()
            atomicFile.finishWrite(output)
        } catch (exc: ReplayStateException) {
            atomicFile.failWrite(output)
            throw exc
        } catch (exc: IOException) {
            atomicFile.failWrite(output)
            throw ReplayStateException("cannot persist replay ledger", exc)
        } catch (exc: RuntimeException) {
            atomicFile.failWrite(output)
            throw ReplayStateException("invalid replay ledger state", exc)
        }
    }

    private fun requireState(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw ReplayStateException(message)
    }

    private companion object {
        const val MAGIC = 0x484D5252 // HMRR
        const val FORMAT_VERSION = 1
        const val MAX_PERSISTED_SESSIONS = 64
        const val MAX_PERSISTED_NONCES = 16_384
        val SESSION_ID = Regex("[A-Za-z0-9_-]{22,128}")
        val NONCE_DIGEST = Regex("[A-Za-z0-9_-]{43}")
    }
}
