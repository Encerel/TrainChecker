package by.innowise.trainchecker

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MonitoringLogEntry::class, PassengerProfile::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(MonitoringLogConverters::class)
abstract class TrainCheckerDatabase : RoomDatabase() {
    abstract fun monitoringLogDao(): MonitoringLogDao
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

        fun getInstance(context: Context): TrainCheckerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrainCheckerDatabase::class.java,
                    "trainchecker.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
