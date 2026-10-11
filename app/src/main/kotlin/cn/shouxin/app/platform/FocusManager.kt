package cn.shouxin.app.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import cn.shouxin.app.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

class FocusManager(private val context: Context, private val db: AppDatabase, private val scheduler: ReminderScheduler) {
    private val mutex = Mutex()
    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    fun remaining(row: FocusRow): Long = (row.durationMillis - (SystemClock.elapsedRealtime() - row.elapsedStarted)).coerceAtLeast(0)
    suspend fun start(task: String, minutes: Int): FocusRow = mutex.withLock {
        require(minutes in 5..120)
        val row = db.withTransaction {
            db.dao().runningFocus()?.let { return@withTransaction it }
            val next = FocusRow(task = task.take(80).ifBlank { "今天的小目标" }, startedAt = System.currentTimeMillis(),
                elapsedStarted = SystemClock.elapsedRealtime(), bootCount = bootCount(), durationMillis = minutes * 60_000L)
            next.copy(id = db.dao().insertFocus(next))
        }
        scheduler.scheduleFocus(row.id, row.startedAt + row.durationMillis)
        row
    }
    suspend fun recover(): FocusRow? = mutex.withLock {
        val row = db.dao().runningFocus() ?: return@withLock null
        val elapsed = SystemClock.elapsedRealtime() - row.elapsedStarted
        val wall = System.currentTimeMillis() - row.startedAt
        if (row.bootCount != bootCount() || elapsed < 0 || abs(wall - elapsed) > 120_000) {
            db.dao().updateFocus(row.copy(state = "INTERRUPTED", actualMillis = 0)); scheduler.cancelFocus(row.id)
            return@withLock null
        }
        if (elapsed >= row.durationMillis) {
            val done = row.copy(state = "COMPLETED", actualMillis = row.durationMillis)
            db.dao().updateFocus(done); scheduler.cancelFocus(row.id); return@withLock done
        }
        scheduler.scheduleFocus(row.id, System.currentTimeMillis() + row.durationMillis - elapsed)
        null
    }
    suspend fun cancel() = mutex.withLock {
        val row = db.dao().runningFocus() ?: return@withLock
        db.dao().updateFocus(row.copy(state = "CANCELLED", actualMillis = (SystemClock.elapsedRealtime() - row.elapsedStarted).coerceIn(0, row.durationMillis)))
        scheduler.cancelFocus(row.id)
    }
}
