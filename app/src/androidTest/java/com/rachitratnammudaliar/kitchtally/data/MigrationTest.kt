package com.rachitratnammudaliar.kitchtally.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Data saved by version 1.0 must survive every update unchanged. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @Test
    fun version1DataIsKept() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { db ->
            // The exact tables Room created in version 1.
            db.execSQL(
                "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, " +
                    "unit TEXT NOT NULL, life TEXT NOT NULL, lowAt REAL NOT NULL, refillNeeded INTEGER NOT NULL, photoFile TEXT NOT NULL, " +
                    "notes TEXT NOT NULL, createdAt INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, type TEXT NOT NULL, " +
                    "epochDay INTEGER NOT NULL, quantity REAL NOT NULL, priceMinor INTEGER, shop TEXT NOT NULL, receiptFile TEXT NOT NULL, " +
                    "note TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX index_events_itemId ON events (itemId)")
            db.execSQL("CREATE INDEX index_events_epochDay ON events (epochDay)")
            db.execSQL("INSERT INTO items VALUES (1, 'Bread', 'Bread & bakery', 'PACKET', 'SHORT', 1.0, 1, '', '', 1)")
            db.execSQL("INSERT INTO items VALUES (2, 'Rice', 'Grains & flour', 'KG', 'LONG', 1.0, 0, '', '5 kg bag', 2)")
            db.execSQL("INSERT INTO events VALUES (1, 2, 'BOUGHT', 20000, 5.0, 1245, 'Maxima', '', '', 3)")
            db.execSQL("INSERT INTO events VALUES (2, 2, 'USED', 20001, 0.5, NULL, '', '', '', 4)")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            runBlocking {
                val dao = room.pantry()
                val items = dao.items().associateBy { it.name }
                assertEquals(2, items.size)
                assertEquals(1.0, items.getValue("Bread").step, 0.0)
                assertEquals(0.5, items.getValue("Rice").step, 0.0)
                assertEquals(false, items.getValue("Rice").hiddenFromList)
                assertEquals(false, items.getValue("Rice").unsure)
                assertEquals(0.0, items.getValue("Rice").packSize, 0.0)
                assertEquals(false, items.getValue("Rice").countMeals)
                assertEquals(0.0, items.getValue("Rice").mealsPerUnit, 0.0)
                assertEquals(false, items.getValue("Rice").lowInMeals)
                // New columns can be written after the upgrade.
                dao.update(items.getValue("Rice").copy(countMeals = true, mealsPerUnit = 6.0))
                assertEquals(6.0, dao.item(items.getValue("Rice").id)!!.mealsPerUnit, 0.0)
                assertEquals(true, items.getValue("Bread").refillNeeded)
                assertEquals("5 kg bag", items.getValue("Rice").notes)
                assertEquals("Staples", items.getValue("Rice").category)
                assertEquals("Bread & bakery", items.getValue("Bread").category)
                assertEquals(4.5, dao.stockOf(2), 0.0)
                assertEquals(1245L, dao.events().first { it.type == "BOUGHT" }.priceMinor)
                dao.insert(ShoppingNote(text = "Candles"))
                assertEquals(listOf("Candles"), dao.notes().map { it.text })
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
