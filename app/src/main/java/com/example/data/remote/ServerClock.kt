package com.example.data.remote

import android.os.SystemClock
import com.example.data.model.ChatMessage
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Provides a server-calibrated monotonic clock resilient to device wall-clock tampering.
 * Computes the server time offset once from Firestore [FieldValue.serverTimestamp] and
 * advances strictly using [SystemClock.elapsedRealtime].
 */
class ServerClock(
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockProvider: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var calibratedServerMillis: Long? = null

    @Volatile
    private var calibratedElapsedMillis: Long? = null

    private val syncMutex = Mutex()

    val isCalibrated: Boolean
        get() = calibratedServerMillis != null && calibratedElapsedMillis != null

    fun calibrate(serverMillis: Long) {
        val elapsedNow = elapsedRealtimeProvider()
        calibratedServerMillis = serverMillis
        calibratedElapsedMillis = elapsedNow
    }

    suspend fun ensureSynced(db: FirebaseFirestore, userId: String): Boolean {
        val baseElapsed = calibratedElapsedMillis
        if (isCalibrated && baseElapsed != null) {
            val ageMs = elapsedRealtimeProvider() - baseElapsed
            if (ageMs in 0..RECALIBRATION_INTERVAL_MILLIS) {
                return true
            }
        }
        return syncWithServer(db, userId)
    }

    suspend fun syncWithServer(db: FirebaseFirestore, userId: String): Boolean {
        if (userId.isBlank()) return false
        return syncMutex.withLock {
            try {
                val userRef = db.collection("users").document(userId)
                userRef.update("updatedAt", FieldValue.serverTimestamp()).await()
                val snap = userRef.get(Source.SERVER).await()
                val serverTs = snap.getTimestamp("updatedAt")
                if (serverTs != null) {
                    calibrate(serverTs.toDate().time)
                    true
                } else {
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    fun nowServerMillis(): Long {
        val baseServer = calibratedServerMillis
        val baseElapsed = calibratedElapsedMillis
        return if (baseServer != null && baseElapsed != null) {
            val delta = (elapsedRealtimeProvider() - baseElapsed).coerceAtLeast(0L)
            baseServer + delta
        } else {
            wallClockProvider()
        }
    }

    fun computeExpiresAt(): Timestamp {
        val expiryMillis = nowServerMillis() + MESSAGE_TTL_MILLIS
        return Timestamp(Date(expiryMillis))
    }

    /**
     * Returns a cutoff strictly >= (serverNow - 3h) so Firestore security rules evaluating
     * `resource.data.createdAt > request.time - duration.value(3, 'h')` never reject queries
     * due to sub-second network/clock jitter.
     */
    fun queryCutoffThreeHoursAgo(safetyBufferMs: Long = QUERY_SAFETY_BUFFER_MILLIS): Timestamp {
        val cutoffMillis = nowServerMillis() - MESSAGE_TTL_MILLIS + safetyBufferMs
        return Timestamp(Date(cutoffMillis))
    }

    fun isTimestampExpired(
        timestamp: Timestamp?,
        nowMs: Long = nowServerMillis(),
        ttlMillis: Long = MESSAGE_TTL_MILLIS
    ): Boolean {
        val tsMillis = timestamp?.toDate()?.time ?: return false
        return tsMillis + ttlMillis <= nowMs
    }

    fun isMessageExpired(
        message: ChatMessage,
        nowMs: Long = nowServerMillis(),
        hasPendingWrites: Boolean = false
    ): Boolean {
        if (message.deleted) return true
        val expiresMillis = message.expiresAt?.toDate()?.time
        if (expiresMillis != null && expiresMillis <= nowMs) return true
        if (!hasPendingWrites) {
            val createdMillis = message.createdAt?.toDate()?.time
            if (createdMillis != null && createdMillis + MESSAGE_TTL_MILLIS <= nowMs) return true
        }
        return false
    }

    companion object {
        const val MESSAGE_TTL_MILLIS: Long = 3L * 60L * 60L * 1000L // 3 hours (10,800,000 ms)
        const val QUERY_SAFETY_BUFFER_MILLIS: Long = 5_000L
        private const val RECALIBRATION_INTERVAL_MILLIS: Long = 15L * 60L * 1000L
    }
}
