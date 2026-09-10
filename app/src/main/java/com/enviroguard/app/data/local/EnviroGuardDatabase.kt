package com.enviroguard.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.enviroguard.app.data.local.dao.SensorReadingDao
import com.enviroguard.app.data.local.entity.SensorReadingEntity


@Database(
    entities = [
        SensorReadingEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class EnviroGuardDatabase : RoomDatabase() {

    abstract fun sensorReadingDao(): SensorReadingDao
    companion object {
        @Volatile
        private var INSTANCE: EnviroGuardDatabase? = null

        fun getInstance(context: Context): EnviroGuardDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    EnviroGuardDatabase::class.java,
                    "enviroguard_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7)
                    .addMigrations(MIGRATION_7_8)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve row IDs and every observation; only measurement nullability changes.
                db.execSQL("CREATE TABLE sensor_readings_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, deviceId TEXT NOT NULL, timestamp INTEGER NOT NULL, temperature REAL, humidity REAL, eco2 REAL, tvoc REAL, noiseLevel REAL)")
                db.execSQL("INSERT INTO sensor_readings_new (id, deviceId, timestamp, temperature, humidity, eco2, tvoc, noiseLevel) SELECT id, deviceId, timestamp, temperature, humidity, eco2, tvoc, noiseLevel FROM sensor_readings")
                db.execSQL("DROP TABLE sensor_readings")
                db.execSQL("ALTER TABLE sensor_readings_new RENAME TO sensor_readings")
                db.execSQL("CREATE UNIQUE INDEX index_sensor_readings_deviceId_timestamp ON sensor_readings(deviceId, timestamp)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep the newest local row if an earlier Firebase risk update
                // caused the same device/timestamp reading to be inserted twice.
                db.execSQL(
                    """DELETE FROM sensor_readings
                       WHERE id NOT IN (
                           SELECT MAX(id) FROM sensor_readings
                           GROUP BY deviceId, timestamp
                       )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sensor_readings_deviceId_timestamp " +
                        "ON sensor_readings(deviceId, timestamp)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS daily_exposure_new (
                        date TEXT NOT NULL PRIMARY KEY,
                        avgErs REAL NOT NULL,
                        peakErs INTEGER NOT NULL,
                        peakTime TEXT NOT NULL,
                        totalHighRiskMinutes INTEGER NOT NULL,
                        recommendation TEXT NOT NULL
                    )""".trimIndent()
                )
                // Rows are copied oldest-to-newest so the newest duplicate date wins.
                db.execSQL(
                    """INSERT OR REPLACE INTO daily_exposure_new
                        (date, avgErs, peakErs, peakTime, totalHighRiskMinutes, recommendation)
                        SELECT date, avgErs, peakErs, peakTime, totalHighRiskMinutes, recommendation
                        FROM daily_exposure ORDER BY id ASC""".trimIndent()
                )
                db.execSQL("DROP TABLE daily_exposure")
                db.execSQL("ALTER TABLE daily_exposure_new RENAME TO daily_exposure")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sensor_readings " +
                        "ADD COLUMN environmentLabel TEXT NOT NULL DEFAULT 'Unspecified'"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sensor_readings " +
                        "ADD COLUMN collectionSessionId TEXT DEFAULT NULL"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE sensor_readings_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, deviceId TEXT NOT NULL, timestamp INTEGER NOT NULL, temperature REAL NOT NULL, humidity REAL NOT NULL, eco2 REAL NOT NULL, tvoc REAL NOT NULL, noiseLevel REAL NOT NULL)")
                db.execSQL("INSERT INTO sensor_readings_new (id, deviceId, timestamp, temperature, humidity, eco2, tvoc, noiseLevel) SELECT id, deviceId, timestamp, temperature, humidity, eco2, tvoc, noiseDb FROM sensor_readings")
                db.execSQL("DROP TABLE sensor_readings")
                db.execSQL("ALTER TABLE sensor_readings_new RENAME TO sensor_readings")
                db.execSQL("CREATE UNIQUE INDEX index_sensor_readings_deviceId_timestamp ON sensor_readings(deviceId, timestamp)")
                db.execSQL("DROP TABLE IF EXISTS risk_assessments")
                db.execSQL("DROP TABLE IF EXISTS daily_exposure")
            }
        }
    }
}
