package com.example.ddlreminder.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-2-3-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 2 to 3 retains existing ddl and creates logs table`() {
        openHelper(version = 2) { db ->
            db.execSQL(V2_DDL_TABLE)
            db.execSQL(
                "INSERT INTO ddls (id,title,deadlineMillis,isCompleted,completedAtMillis," +
                    "createdAtMillis,updatedAtMillis,reminderTypesCsv) " +
                    "VALUES (1,'existing private title',123456,0,NULL,1,1," +
                    "'FIVE_DAYS,ONE_DAY')",
            )
        }.close()

        val upgraded = openHelper(version = 3, onUpgrade = { db ->
            AppDatabase.MIGRATION_2_3.migrate(db)
        })
        val db = upgraded.writableDatabase
        db.query("SELECT id, title, deadlineMillis FROM ddls WHERE id = 1").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("existing private title", cursor.getString(1))
            assertEquals(123456L, cursor.getLong(2))
        }
        db.query("SELECT COUNT(*) FROM event_logs").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        upgraded.close()
    }

    @Test
    fun `migration 3 to 4 maps old reminders and retains ddl and event logs`() {
        openHelper(version = 3) { db ->
            db.execSQL(V2_DDL_TABLE)
            db.execSQL(
                "CREATE TABLE event_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                    "eventType TEXT NOT NULL,eventTime INTEGER NOT NULL,ddlId INTEGER," +
                    "deadlineMillis INTEGER,reminderType TEXT,metadataJson TEXT)",
            )
            db.execSQL("CREATE INDEX index_event_logs_eventType_ddlId ON event_logs (eventType, ddlId)")
            db.execSQL(
                "INSERT INTO ddls (id,title,deadlineMillis,isCompleted,completedAtMillis,createdAtMillis," +
                    "updatedAtMillis,reminderTypesCsv) VALUES (1,'keep me',999999,0,NULL,1,1," +
                    "'FIVE_DAYS,ONE_DAY,ONE_HOUR,THIRTY_MINUTES')",
            )
            db.execSQL("INSERT INTO event_logs (id,eventType,eventTime,ddlId) VALUES (8,'app_opened',123,NULL)")
        }.close()

        val upgraded = openHelper(version = 4, onUpgrade = { AppDatabase.MIGRATION_3_4.migrate(it) })
        val db = upgraded.writableDatabase
        db.query("SELECT title, reminderOffsetsCsv FROM ddls WHERE id=1").use {
            assertEquals(true, it.moveToFirst())
            assertEquals("keep me", it.getString(0))
            assertEquals("7200,1440,60,30", it.getString(1))
        }
        db.query("SELECT eventType,eventTime FROM event_logs WHERE id=8").use {
            assertEquals(true, it.moveToFirst())
            assertEquals("app_opened", it.getString(0))
            assertEquals(123L, it.getLong(1))
        }
        upgraded.close()
    }

    @Test
    fun `migration 4 to 5 adds notes and retains ddl reminders and logs`() {
        openHelper(version = 4) { db ->
            db.execSQL(V4_DDL_TABLE)
            db.execSQL("CREATE TABLE event_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,eventType TEXT NOT NULL,eventTime INTEGER NOT NULL,ddlId INTEGER,deadlineMillis INTEGER,reminderType TEXT,metadataJson TEXT)")
            db.execSQL("CREATE INDEX index_event_logs_eventType_ddlId ON event_logs (eventType, ddlId)")
            db.execSQL("INSERT INTO ddls (id,title,deadlineMillis,isCompleted,completedAtMillis,createdAtMillis,updatedAtMillis,reminderTypesCsv,reminderOffsetsCsv) VALUES (1,'existing',999,0,NULL,1,1,'ONE_HOUR','60')")
            db.execSQL("INSERT INTO event_logs (id,eventType,eventTime) VALUES (2,'app_opened',5)")
        }.close()
        val upgraded = openHelper(version = 5, onUpgrade = { AppDatabase.MIGRATION_4_5.migrate(it) })
        upgraded.writableDatabase.query("SELECT title,reminderOffsetsCsv,notes FROM ddls WHERE id=1").use {
            assertEquals(true, it.moveToFirst()); assertEquals("existing", it.getString(0)); assertEquals("60", it.getString(1)); assertEquals(true, it.isNull(2))
        }
        upgraded.writableDatabase.query("SELECT COUNT(*) FROM event_logs").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        upgraded.close()
    }

    @Test
    fun `migration gives every old task null notes without changing completion status`() {
        openHelper(version = 4) { db ->
            db.execSQL(V4_DDL_TABLE)
            db.execSQL("INSERT INTO ddls (id,title,deadlineMillis,isCompleted,completedAtMillis,createdAtMillis,updatedAtMillis) VALUES (1,'unfinished',999,0,NULL,1,1),(2,'completed',888,1,777,2,3)")
        }.close()
        val upgraded = openHelper(version = 5, onUpgrade = { AppDatabase.MIGRATION_4_5.migrate(it) })
        upgraded.writableDatabase.query("SELECT id,title,isCompleted,completedAtMillis,notes FROM ddls ORDER BY id").use {
            assertEquals(2, it.count)
            assertEquals(true, it.moveToFirst())
            assertEquals("unfinished", it.getString(1))
            assertEquals(0, it.getInt(2))
            assertEquals(true, it.isNull(4))
            assertEquals(true, it.moveToNext())
            assertEquals("completed", it.getString(1))
            assertEquals(1, it.getInt(2))
            assertEquals(777L, it.getLong(3))
            assertEquals(true, it.isNull(4))
        }
        upgraded.close()
    }

    private fun openHelper(
        version: Int,
        onUpgrade: ((SupportSQLiteDatabase) -> Unit)? = null,
        onCreate: ((SupportSQLiteDatabase) -> Unit)? = null,
    ): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) { onCreate?.invoke(db) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    onUpgrade?.invoke(db)
                }
            }).build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase
        }
    }

    private companion object {
        const val V2_DDL_TABLE =
            "CREATE TABLE ddls (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "title TEXT NOT NULL," +
                "deadlineMillis INTEGER NOT NULL," +
                "isCompleted INTEGER NOT NULL," +
                "completedAtMillis INTEGER," +
                "createdAtMillis INTEGER NOT NULL," +
                "updatedAtMillis INTEGER NOT NULL," +
                "reminderTypesCsv TEXT NOT NULL DEFAULT 'FIVE_DAYS,THREE_DAYS,ONE_DAY,TWELVE_HOURS')"
        const val V4_DDL_TABLE =
            "CREATE TABLE ddls (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,title TEXT NOT NULL,deadlineMillis INTEGER NOT NULL," +
                "isCompleted INTEGER NOT NULL,completedAtMillis INTEGER,createdAtMillis INTEGER NOT NULL,updatedAtMillis INTEGER NOT NULL," +
                "reminderTypesCsv TEXT NOT NULL DEFAULT 'FIVE_DAYS,THREE_DAYS,ONE_DAY,TWELVE_HOURS'," +
                "reminderOffsetsCsv TEXT NOT NULL DEFAULT '7200,4320,1440,720')"
    }
}
