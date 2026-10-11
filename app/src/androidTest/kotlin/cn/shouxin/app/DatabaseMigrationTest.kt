package cn.shouxin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import cn.shouxin.app.data.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun addingFocusNotePreservesExistingSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE focus_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, task TEXT NOT NULL, startedAt INTEGER NOT NULL, elapsedStarted INTEGER NOT NULL, bootCount INTEGER NOT NULL, durationMillis INTEGER NOT NULL, actualMillis INTEGER NOT NULL, state TEXT NOT NULL)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO focus_sessions VALUES (17, '已有的小目标', 1000, 100, 1, 600000, 600000, 'COMPLETED')")
            AppDatabase.MIGRATION_1_2.migrate(db)
            db.query("SELECT id, task, durationMillis, state, note FROM focus_sessions").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(17L, cursor.getLong(0)); assertEquals("已有的小目标", cursor.getString(1))
                assertEquals(600000L, cursor.getLong(2)); assertEquals("COMPLETED", cursor.getString(3)); assertEquals("", cursor.getString(4))
                assertFalse(cursor.moveToNext())
            }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
