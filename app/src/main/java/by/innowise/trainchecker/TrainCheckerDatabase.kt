package by.innowise.trainchecker

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MonitoringLogEntry::class, PassengerProfile::class, WebViewTechnicalLogEntry::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(MonitoringLogConverters::class)
abstract class TrainCheckerDatabase : RoomDatabase() {
    abstract fun monitoringLogDao(): MonitoringLogDao
    abstract fun webViewTechnicalLogDao(): WebViewTechnicalLogDao
    abstract fun passengerProfileDao(): PassengerProfileDao

    companion object {
        @Volatile
        private var INSTANCE: TrainCheckerDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS passenger_profiles (
                        name TEXT NOT NULL,
                        lastName TEXT NOT NULL,
                        firstName TEXT NOT NULL,
                        middleName TEXT NOT NULL,
                        documentNumber TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(name)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE passenger_profiles ADD COLUMN chatId TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE passenger_profiles ADD COLUMN rwLogin TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE passenger_profiles ADD COLUMN hasSavedRwPassword INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS webview_technical_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        routeId INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        action TEXT NOT NULL,
                        message TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_webview_technical_logs_routeId_timestamp ON webview_technical_logs(routeId, timestamp)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_webview_technical_logs_timestamp ON webview_technical_logs(timestamp)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_webview_technical_logs_state ON webview_technical_logs(state)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_webview_technical_logs_action ON webview_technical_logs(action)"
                )
                db.execSQL(
                    """
                    DELETE FROM monitoring_logs
                    WHERE message LIKE 'JS#%'
                       OR message LIKE 'WAIT[%'
                       OR message LIKE 'STATE[%'
                       OR message LIKE 'SCROLL_REQUEST[%'
                       OR message LIKE 'WEBVIEW LOAD_ROUTE%'
                       OR message LIKE 'WEBVIEW PAGE_STARTED%'
                       OR message LIKE 'WEBVIEW PAGE_FINISHED%'
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): TrainCheckerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrainCheckerDatabase::class.java,
                    "trainchecker.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
