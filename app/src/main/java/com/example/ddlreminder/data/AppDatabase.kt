package com.example.ddlreminder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.ddlreminder.logging.EventLogDao
import com.example.ddlreminder.logging.EventLogEntity

@Database(entities = [DdlEntity::class, EventLogEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ddlDao(): DdlDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ddl_reminder.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }

        /** 旧任务保留 V1.0 原有的四个固定提醒，不会丢数据。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ddls ADD COLUMN reminderTypesCsv TEXT NOT NULL " +
                        "DEFAULT 'FIVE_DAYS,THREE_DAYS,ONE_DAY,TWELVE_HOURS'",
                )
            }
        }

        /** 只新增独立日志表，不修改或重建 ddls，因此已有任务保持原样。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`eventType` TEXT NOT NULL, " +
                        "`eventTime` INTEGER NOT NULL, " +
                        "`ddlId` INTEGER, " +
                        "`deadlineMillis` INTEGER, " +
                        "`reminderType` TEXT, " +
                        "`metadataJson` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_logs_eventType_ddlId` " +
                        "ON `event_logs` (`eventType`, `ddlId`)",
                )
            }
        }

        /** 将六种旧枚举无损映射为分钟 offset，同时保留旧列与 event_logs。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ddls ADD COLUMN reminderOffsetsCsv TEXT NOT NULL " +
                        "DEFAULT '7200,4320,1440,720'",
                )
                db.execSQL(
                    "UPDATE ddls SET reminderOffsetsCsv = " +
                        "RTRIM(" +
                        "CASE WHEN instr(',' || reminderTypesCsv || ',', ',FIVE_DAYS,') > 0 THEN '7200,' ELSE '' END || " +
                        "CASE WHEN instr(',' || reminderTypesCsv || ',', ',THREE_DAYS,') > 0 THEN '4320,' ELSE '' END || " +
                        "CASE WHEN instr(',' || reminderTypesCsv || ',', ',ONE_DAY,') > 0 THEN '1440,' ELSE '' END || " +
                        "CASE WHEN instr(',' || reminderTypesCsv || ',', ',TWELVE_HOURS,') > 0 THEN '720,' ELSE '' END || " +
                        "CASE WHEN instr(',' || reminderTypesCsv || ',', ',ONE_HOUR,') > 0 THEN '60,' ELSE '' END || " +
                        "CASE WHEN instr(',' || reminderTypesCsv || ',', ',THIRTY_MINUTES,') > 0 THEN '30,' ELSE '' END, ',')",
                )
            }
        }

        /** V1.2.1 为 DDL 增加可空备注，不重建表，也不触碰提醒和匿名日志。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ddls ADD COLUMN notes TEXT")
            }
        }
    }
}
