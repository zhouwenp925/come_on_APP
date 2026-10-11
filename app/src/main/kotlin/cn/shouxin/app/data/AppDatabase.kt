package cn.shouxin.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.shouxin.core.Rule
import cn.shouxin.core.RuleType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleRow(
    @PrimaryKey val id: String, val version: Int, val type: String, val enabled: Boolean,
    val hour: Int, val minute: Int, val intervalMinutes: Int, val thresholdMinutes: Int,
    val windowStart: Int, val windowEnd: Int, val weekdays: String, val style: String
) {
    fun model() = Rule(id, version, RuleType.valueOf(type), enabled, hour, minute, intervalMinutes, thresholdMinutes, windowStart, windowEnd, weekdays.split(',').mapNotNull { it.toIntOrNull() }.toSet(), style)
    companion object { fun of(r: Rule) = RuleRow(r.id, r.version, r.type.name, r.enabled, r.hour, r.minute, r.intervalMinutes, r.thresholdMinutes, r.windowStart, r.windowEnd, r.weekdays.sorted().joinToString(","), r.style) }
}
@Entity(tableName = "usage_events")
data class EventRow(@PrimaryKey val key: String, val at: Long, val packageName: String, val kind: String, val instanceId: Int)
@Entity(tableName = "daily_usage", primaryKeys = ["date", "packageName"])
data class DailyRow(val date: String, val packageName: String, val millis: Long, val updatedAt: Long, val quality: String)
@Entity(tableName = "notification_log", indices = [Index(value = ["key"], unique = true)])
data class NotificationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val key: String, val ruleId: String,
    val ruleVersion: Int, val createdAt: Long, val date: String, val expiresAt: Long,
    val state: String, val type: String, val contentId: String, val title: String, val body: String,
    val snoozeCount: Int = 0
)
@Entity(tableName = "focus_sessions")
data class FocusRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val task: String, val startedAt: Long,
    val elapsedStarted: Long, val bootCount: Int, val durationMillis: Long, val actualMillis: Long = 0,
    val state: String = "RUNNING", @ColumnInfo(defaultValue = "''") val note: String = ""
)
@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val key: String, val value: String)
@Entity(tableName = "snoozes")
data class SnoozeRow(@PrimaryKey val key: String, val originId: Long, val ruleId: String, val ruleVersion: Int, val at: Long)

@Dao
interface AppDao {
    @Query("SELECT * FROM rules ORDER BY type, id") fun rulesFlow(): Flow<List<RuleRow>>
    @Query("SELECT * FROM rules ORDER BY type, id") suspend fun rules(): List<RuleRow>
    @Query("SELECT * FROM rules WHERE id=:id") suspend fun rule(id: String): RuleRow?
    @Upsert suspend fun saveRule(row: RuleRow)
    @Query("DELETE FROM rules WHERE id=:id") suspend fun deleteRule(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvents(rows: List<EventRow>)
    @Query("SELECT * FROM usage_events WHERE at>=:start ORDER BY at, key") suspend fun events(start: Long): List<EventRow>
    @Query("SELECT * FROM daily_usage ORDER BY date DESC, millis DESC") fun dailyFlow(): Flow<List<DailyRow>>
    @Query("SELECT * FROM daily_usage ORDER BY date, packageName") suspend fun dailyExport(): List<DailyRow>
    @Query("SELECT * FROM notification_log ORDER BY createdAt") suspend fun notificationsExport(): List<NotificationRow>
    @Query("SELECT * FROM focus_sessions ORDER BY startedAt") suspend fun focusExport(): List<FocusRow>
    @Query("DELETE FROM daily_usage WHERE date=:date") suspend fun deleteDay(date: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDaily(rows: List<DailyRow>)
    @Query("SELECT * FROM notification_log ORDER BY createdAt DESC LIMIT 100") fun notificationsFlow(): Flow<List<NotificationRow>>
    @Query("SELECT * FROM notification_log WHERE key=:key") suspend fun notification(key: String): NotificationRow?
    @Query("SELECT * FROM notification_log WHERE id=:id") suspend fun notificationById(id: Long): NotificationRow?
    @Query("SELECT * FROM notification_log WHERE state='PENDING'") suspend fun pendingNotifications(): List<NotificationRow>
    @Query("SELECT * FROM notification_log WHERE state IN ('PENDING','SUBMITTED') AND type!='FOCUS_END' AND (date=:date OR createdAt>=:rollingStart)") suspend fun history(date: String, rollingStart: Long): List<NotificationRow>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertNotification(row: NotificationRow): Long
    @Query("UPDATE notification_log SET state=:state WHERE id=:id") suspend fun markNotification(id: Long, state: String)
    @Query("UPDATE notification_log SET snoozeCount=snoozeCount+1 WHERE id=:id") suspend fun incrementSnooze(id: Long)
    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC LIMIT 100") fun focusFlow(): Flow<List<FocusRow>>
    @Query("SELECT * FROM focus_sessions WHERE state='RUNNING' LIMIT 1") suspend fun runningFocus(): FocusRow?
    @Query("SELECT * FROM focus_sessions WHERE id=:id") suspend fun focus(id: Long): FocusRow?
    @Insert suspend fun insertFocus(row: FocusRow): Long
    @Update suspend fun updateFocus(row: FocusRow)
    @Query("SELECT value FROM meta WHERE `key`=:key") suspend fun meta(key: String): String?
    @Upsert suspend fun saveMeta(row: MetaRow)
    @Query("SELECT * FROM snoozes") suspend fun snoozes(): List<SnoozeRow>
    @Query("SELECT * FROM snoozes WHERE `key`=:key") suspend fun snooze(key: String): SnoozeRow?
    @Upsert suspend fun saveSnooze(row: SnoozeRow)
    @Query("DELETE FROM snoozes WHERE `key`=:key") suspend fun deleteSnooze(key: String)
    @Query("DELETE FROM snoozes") suspend fun clearSnoozes()
    @Query("DELETE FROM usage_events") suspend fun clearEvents()
    @Query("DELETE FROM daily_usage") suspend fun clearDaily()
    @Query("DELETE FROM notification_log") suspend fun clearNotifications()
    @Query("DELETE FROM focus_sessions") suspend fun clearFocus()
    @Query("DELETE FROM meta") suspend fun clearMeta()
    @Query("DELETE FROM usage_events WHERE at<:before") suspend fun pruneEvents(before: Long)
    @Query("DELETE FROM daily_usage WHERE date<:before") suspend fun pruneDaily(before: String)
    @Query("DELETE FROM notification_log WHERE createdAt<:before") suspend fun pruneNotifications(before: Long)
    @Query("DELETE FROM focus_sessions WHERE startedAt<:before AND state!='RUNNING'") suspend fun pruneFocus(before: Long)
}
@Database(entities = [RuleRow::class, EventRow::class, DailyRow::class, NotificationRow::class, FocusRow::class, MetaRow::class, SnoozeRow::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE focus_sessions ADD COLUMN note TEXT NOT NULL DEFAULT ''") }
        }
        fun open(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "shouxin.db")
            .addMigrations(MIGRATION_1_2).build()
    }
}
