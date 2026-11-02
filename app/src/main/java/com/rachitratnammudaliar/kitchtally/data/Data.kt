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

    @Query("UPDATE items SET refillNeeded = 0 WHERE id = :id")
    suspend fun clearRefill(id: Long)

    @Transaction
    suspend fun insertItemWithEvents(item: Item, events: List<Event>): Long {
        val id = insert(item)
        events.forEach { insert(it.copy(itemId = id)) }
        return id
    }
}

@Database(entities = [Item::class, Event::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pantry(): PantryDao

    companion object {
        const val DATABASE_NAME = "kitchtally.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                    .build().also { instance = it }
            }
    }
}
