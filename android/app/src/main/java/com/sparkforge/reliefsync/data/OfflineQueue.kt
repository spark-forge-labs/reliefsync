package com.sparkforge.reliefsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class QueuedSos(
    val id: String, val text: String?, val audioPath: String?, val lat: Double?, val lon: Double?,
    val createdAt: Long, val attempts: Int,
)

/** Offline-first SQLite queue for SOS voice notes (spec 4.2). Survives app kills and reboots. */
class OfflineQueue private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "reliefsync_queue.db", null, 1) {

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    init { _count.value = all().size }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE queue (id TEXT PRIMARY KEY, text TEXT, audio TEXT, lat REAL, lon REAL, " +
                "created INTEGER, attempts INTEGER DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun add(text: String?, audio: File?, lat: Double?, lon: Double?): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insert("queue", null, ContentValues().apply {
            put("id", id); put("text", text); put("audio", audio?.absolutePath)
            if (lat != null) put("lat", lat); if (lon != null) put("lon", lon)
            put("created", System.currentTimeMillis())
        })
        _count.value = all().size
        return id
    }

    fun all(): List<QueuedSos> = readableDatabase.rawQuery("SELECT * FROM queue ORDER BY created", null).use { c ->
        buildList {
            while (c.moveToNext()) add(
                QueuedSos(
                    c.getString(0), c.getString(1), c.getString(2),
                    if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4),
                    c.getLong(5), c.getInt(6),
                )
            )
        }
    }

    fun remove(id: String) {
        writableDatabase.delete("queue", "id=?", arrayOf(id)); _count.value = all().size
    }

    fun bump(id: String) = writableDatabase.execSQL("UPDATE queue SET attempts=attempts+1 WHERE id=?", arrayOf(id))

    companion object {
        @Volatile private var inst: OfflineQueue? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: OfflineQueue(ctx.applicationContext).also { inst = it } }

        /** Expedited, network-constrained flush. Fires automatically when connectivity returns. */
        fun scheduleSync(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sos-sync", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val q = OfflineQueue.get(applicationContext)
        val prefs = Prefs(applicationContext)
        val api = Api(prefs)
        var failed = false
        for (item in q.all()) {
            try {
                api.sendSos(item.text, item.audioPath?.let(::File), item.lat, item.lon, item.id, prefs.phone)
                q.remove(item.id)
                item.audioPath?.let { File(it).delete() }
            } catch (e: Exception) {
                q.bump(item.id); failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo() =
        com.sparkforge.reliefsync.notify.Notifier.syncForegroundInfo(applicationContext)
}
