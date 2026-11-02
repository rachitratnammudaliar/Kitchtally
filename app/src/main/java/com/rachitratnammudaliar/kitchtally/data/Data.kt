package com.rachitratnammudaliar.kitchtally.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rachitratnammudaliar.kitchtally.domain.Entry
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Life
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Something you keep at home: rice, bread, shampoo. */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "Groceries",
    val unit: String = QtyUnit.PACKET.name,
    val life: String = Life.MEDIUM.name,
    /** Running low at or below this amount. */
    val lowAt: Double = 1.0,
    /** Put on the shopping list by hand, even if there's still some left. */
    val refillNeeded: Boolean = false,
    /** File name of the item's photo in the app's private storage, or empty. */
    val photoFile: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // Added in database version 2 (app 1.1.0).
    /** How much one tap of − or Shared takes, e.g. 0.5 kg of rice. */
    val step: Double = 1.0,
    /** Kept off the shopping list until it's bought again. */
    val hiddenFromList: Boolean = false,
)

/** Something to buy that isn't a pantry item, typed on the shopping list. */
@Entity(tableName = "shopping_notes")
data class ShoppingNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One logged change to an item's stock. */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = Item::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("itemId"), Index("epochDay")],
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val type: String,
    val epochDay: Long,
    /** Always positive, except ADJUST which can be negative. */
    val quantity: Double,
    /** Total paid, in cents, for BOUGHT. Null if not entered. */
    val priceMinor: Long? = null,
    val shop: String = "",
    /** File name of a receipt photo, or empty. */
    val receiptFile: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

fun Item.unitValue(): QtyUnit = QtyUnit.fromName(unit)

fun Item.lifeValue(): Life = Life.fromName(life)

fun Event.typeValue(): EventType = EventType.fromName(type)

fun Event.date(): LocalDate = LocalDate.ofEpochDay(epochDay)

fun Event.toEntry(): Entry = Entry(typeValue(), date(), quantity, priceMinor, shop)

@Dao
interface PantryDao {
    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE")
    fun observeItems(): Flow<List<Item>>

    @Query("SELECT * FROM events ORDER BY epochDay DESC, createdAt DESC")
    fun observeEvents(): Flow<List<Event>>

    @Query("SELECT * FROM shopping_notes ORDER BY createdAt")
    fun observeNotes(): Flow<List<ShoppingNote>>

    @Query("SELECT * FROM shopping_notes")
    suspend fun notes(): List<ShoppingNote>

    @Insert
    suspend fun insert(note: ShoppingNote): Long

    @Query("DELETE FROM shopping_notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    /** Current stock, read straight from the database so quick taps never go below zero. */
    @Query("SELECT COALESCE(SUM(CASE WHEN type IN ('BOUGHT', 'ADJUST') THEN quantity ELSE -quantity END), 0) FROM events WHERE itemId = :id")
    suspend fun stockOf(id: Long): Double

    @Query("UPDATE items SET refillNeeded = :needed WHERE id = :id")
    suspend fun setRefill(id: Long, needed: Boolean)

    @Query("SELECT * FROM items")
    suspend fun items(): List<Item>

    @Query("SELECT * FROM events")
    suspend fun events(): List<Event>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun item(id: Long): Item?

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun event(id: Long): Event?

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)

    @Insert
    suspend fun insert(event: Event): Long

    @Update
    suspend fun update(event: Event)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    /** After buying: off the manual list, and shown again if it was hidden. */
    @Query("UPDATE items SET refillNeeded = 0, hiddenFromList = 0 WHERE id = :id")
    suspend fun clearRefill(id: Long)

    @Transaction
    suspend fun insertItemWithEvents(item: Item, events: List<Event>): Long {
        val id = insert(item)
        events.forEach { insert(it.copy(itemId = id)) }
        return id
    }
}

/**
 * Version 1 -> 2 (app 1.0.0 -> 1.1.0): per-item "uses per tap" amount, "hide until bought",
 * and free-text shopping notes. Kilos and litres start at half a unit per tap.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN step REAL NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE items ADD COLUMN hiddenFromList INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE items SET step = 0.5 WHERE unit IN ('KG', 'LITRE')")
        db.execSQL("CREATE TABLE IF NOT EXISTS shopping_notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }
}

@Database(entities = [Item::class, Event::class, ShoppingNote::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pantry(): PantryDao

    companion object {
        const val DATABASE_NAME = "kitchtally.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
