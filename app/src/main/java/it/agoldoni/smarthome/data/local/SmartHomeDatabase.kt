package it.agoldoni.smarthome.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DeviceEntity::class], version = 2, exportSchema = true)
abstract class SmartHomeDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}

/**
 * Aggiunge il topic di disponibilita.
 *
 * Va scritta a mano invece di lasciar ricreare la tabella: qui dentro ci sono i
 * dispositivi che l'utente ha registrato uno per uno, e perderli per una colonna
 * in piu sarebbe un pessimo scambio. I due payload hanno un default perche la
 * colonna e NOT NULL e le righe gia esistenti devono pur valere qualcosa; il
 * topic resta nullo, che e il modo di dire "questo dispositivo non lo dichiara".
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN availabilityTopic TEXT")
        db.execSQL("ALTER TABLE devices ADD COLUMN payloadAvailable TEXT NOT NULL DEFAULT 'online'")
        db.execSQL("ALTER TABLE devices ADD COLUMN payloadUnavailable TEXT NOT NULL DEFAULT 'offline'")
    }
}
