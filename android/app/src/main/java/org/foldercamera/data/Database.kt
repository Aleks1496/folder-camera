package org.foldercamera.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "captures")
data class Capture(
    @PrimaryKey val photoId: String,
    val filename: String,
    val capturedAt: Long,
    val relativePath: String,
    val baseTreeUri: String,
    val destinationDocumentUri: String? = null,
    val stagingPath: String? = null,
    val mimeType: String = "image/jpeg",
    val byteSize: Long = 0,
    val sha256: String = "",
    val state: String = "CAPTURING",
    val lastLocalError: String? = null,
    val receiverId: String? = null,
    val creationStarted: Boolean = false,
)
val Capture.hasLocalCopy: Boolean get() = state == "SAVED" || state == "DELETE_PENDING"

@Entity(tableName = "deliveries", primaryKeys = ["photoId", "receiverId"],
    foreignKeys = [ForeignKey(entity = Capture::class, parentColumns = ["photoId"], childColumns = ["photoId"])], indices = [Index("photoId")])
data class Delivery(
    val photoId: String, val receiverId: String, val state: String = "PENDING",
    val attempts: Int = 0, val lastAttempt: Long = 0, val nextRetry: Long = 0,
    val leaseUntil: Long = 0, val claim: String? = null,
    val lastError: String? = null, val receipt: String? = null,
)
@Dao
abstract class CameraDao {
    @Insert abstract suspend fun insert(capture: Capture)
    @Update abstract suspend fun update(capture: Capture)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun enqueue(delivery: Delivery)
    @Query("SELECT * FROM captures ORDER BY capturedAt DESC") abstract fun observeCaptures(): Flow<List<Capture>>
    @Query("SELECT * FROM deliveries") abstract fun observeDeliveries(): Flow<List<Delivery>>
    @Query("SELECT * FROM captures WHERE photoId = :id") abstract suspend fun capture(id: String): Capture?
    @Query("SELECT * FROM captures WHERE state NOT IN ('SAVED', 'DELETE_PENDING', 'LOCAL_DELETED')") abstract suspend fun incomplete(): List<Capture>
    @Query("SELECT * FROM captures WHERE state = 'SAVED'") abstract suspend fun saved(): List<Capture>
    @Query("SELECT * FROM captures WHERE state IN ('SAVED', 'DELETE_PENDING', 'LOCAL_DELETED') AND stagingPath IS NOT NULL")
    abstract suspend fun stagingCleanup(): List<Capture>
    @Query("UPDATE captures SET stagingPath = NULL WHERE photoId = :id AND stagingPath = :path")
    abstract suspend fun clearStaging(id: String, path: String)
    @Query("SELECT photoId FROM captures WHERE state = 'DELETE_PENDING'")
    abstract suspend fun pendingLocalDeletes(): List<String>
    @Query("UPDATE captures SET state = 'DELETE_PENDING' WHERE photoId = :id AND state = 'SAVED' AND NOT EXISTS (SELECT 1 FROM deliveries WHERE photoId = :id AND state NOT IN ('RECEIVED', 'DISCARDED'))")
    abstract suspend fun scheduleLocalDelete(id: String): Int
    @Query("UPDATE captures SET state = 'LOCAL_DELETED', lastLocalError = NULL, destinationDocumentUri = NULL WHERE photoId = :id AND state = 'DELETE_PENDING'")
    abstract suspend fun markLocalDeleted(id: String)
    @Query("UPDATE captures SET lastLocalError = 'delete_failed' WHERE photoId = :id AND state = 'DELETE_PENDING'")
    abstract suspend fun markLocalDeleteFailed(id: String)
    @Query("SELECT * FROM deliveries WHERE receiverId = :receiver AND (state = 'PENDING' OR (state = 'SENDING' AND leaseUntil <= :now)) AND nextRetry <= :now ORDER BY lastAttempt LIMIT 1")
    abstract suspend fun candidate(receiver: String, now: Long): Delivery?
    @Query("UPDATE deliveries SET state = 'SENDING', claim = :claim, leaseUntil = :until, attempts = attempts + 1, lastAttempt = :now WHERE photoId = :id AND receiverId = :receiver AND (state = 'PENDING' OR (state = 'SENDING' AND leaseUntil <= :now))")
    abstract suspend fun acquire(id: String, receiver: String, now: Long, until: Long, claim: String): Int
    @Transaction open suspend fun claim(receiver: String, now: Long): Delivery? {
        val d = candidate(receiver, now) ?: return null
        val token = java.util.UUID.randomUUID().toString()
        return if (acquire(d.photoId, receiver, now, now + 600_000, token) == 1)
            d.copy(state = "SENDING", claim = token, attempts = d.attempts + 1, lastAttempt = now, leaseUntil = now + 600_000) else null
    }
    @Query("UPDATE deliveries SET state = :state, claim = NULL, leaseUntil = 0, nextRetry = :next, lastError = :error, receipt = :receipt WHERE photoId = :id AND receiverId = :receiver AND claim = :claim")
    abstract suspend fun finish(id: String, receiver: String, claim: String, state: String, next: Long, error: String?, receipt: String?): Int
    @Transaction open suspend fun finishReceived(id: String, receiver: String, claim: String, receipt: String, deleteLocal: Boolean): Boolean {
        val confirmed = finish(id, receiver, claim, "RECEIVED", 0, null, receipt) == 1
        return confirmed && deleteLocal && scheduleLocalDelete(id) == 1
    }
    @Transaction open suspend fun enqueueSaved(id: String, receiver: String) {
        if (capture(id)?.state == "SAVED") enqueue(Delivery(id, receiver))
    }
    @Query("SELECT COUNT(*) FROM deliveries WHERE receiverId = :receiver AND state IN ('PENDING','SENDING')") abstract suspend fun eligible(receiver: String): Int
    @Query("UPDATE deliveries SET nextRetry = 0 WHERE state = 'PENDING'") abstract suspend fun reconnect()
    @Query("UPDATE deliveries SET state = 'PENDING', nextRetry = 0, lastError = NULL WHERE state IN ('PENDING','FAILED') AND receiverId = :receiver") abstract suspend fun retry(receiver: String)
    @Query("UPDATE deliveries SET state = 'DISCARDED', claim = NULL, leaseUntil = 0 WHERE receiverId = :receiver AND state != 'RECEIVED'") abstract suspend fun discard(receiver: String)
    @Query("SELECT COUNT(*) FROM deliveries WHERE receiverId = :receiver AND state NOT IN ('RECEIVED','DISCARDED')") abstract suspend fun outstanding(receiver: String): Int
    @Transaction open suspend fun markSaved(c: Capture) {
        update(c.copy(state = "SAVED", lastLocalError = null))
        c.receiverId?.let { enqueue(Delivery(c.photoId, it)) }
    }
}
@Database(entities = [Capture::class, Delivery::class], version = 1, exportSchema = true)
abstract class CameraDatabase : RoomDatabase() { abstract fun dao(): CameraDao }
