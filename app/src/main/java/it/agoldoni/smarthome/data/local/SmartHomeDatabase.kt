package it.agoldoni.smarthome.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DeviceEntity::class], version = 6, exportSchema = true)
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

/**
 * Aggiunge il campo JSON della potenza istantanea.
 *
 * Resta nulla per i dispositivi gia registrati, e va bene cosi: nulla vuol dire
 * "di questo non si sa se pubblichi i watt", che e esattamente quello che l'app
 * sapeva di loro fino a un istante fa. Il numero compare quando qualcuno scrive
 * da quale campo leggerlo.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN powerJsonKey TEXT")
    }
}

/**
 * Aggiunge il topic dei consumi accumulati e i due campi da leggerci dentro.
 *
 * Tre colonne nulle, come sempre: nullo vuol dire "di questo dispositivo non
 * conta nessuno", che e la verita per ogni dispositivo registrato finora.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN energyTopic TEXT")
        db.execSQL("ALTER TABLE devices ADD COLUMN energyTodayJsonKey TEXT")
        db.execSQL("ALTER TABLE devices ADD COLUMN energyMonthJsonKey TEXT")
    }
}

/**
 * Aggiunge l'identita' del dispositivo nel registro condiviso.
 *
 * Stringa vuota per le righe che ci sono gia', e non e' un ripiego: vuol dire
 * "questo dispositivo nessun registro lo ha mai nominato", che e' la verita' per
 * ogni dispositivo registrato finora. Da li' passa l'adozione — il primo
 * registro che arriva riconosce dal topic di stato quelli registrati a mano e
 * gli da' un uuid, invece di cancellarli e rifarli con un id nuovo.
 *
 * Additiva come le tre precedenti, e per la stessa ragione: qui dentro ci sono
 * dispositivi inseriti uno per uno a mano.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Aggiunge il posto del dispositivo nell'elenco.
 *
 * Nulla per le righe che ci sono gia', e come per le quattro migrazioni
 * precedenti il nullo dice una verita' invece di ripiegare: di questi
 * dispositivi nessuno ha mai deciso l'ordine, ed e' vero — fino a ieri l'ordine
 * lo decideva l'alfabeto per conto suo. Chi non ha posizione va in fondo e resta
 * in ordine di nome, quindi l'elenco del primo avvio dopo l'aggiornamento e'
 * identico a quello dell'ultimo avvio prima.
 *
 * Nessun `DEFAULT`: uno zero come predefinito darebbe a tutti il **primo**
 * posto, che e' il contrario di "nessun posto".
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN position INTEGER")
    }
}
