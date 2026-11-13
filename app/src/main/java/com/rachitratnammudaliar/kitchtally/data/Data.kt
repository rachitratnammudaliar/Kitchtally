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
import com.rachitratnammudaliar.kitchtally.domain.Categories
import com.rachitratnammudaliar.kitchtally.domain.Counting
import com.rachitratnammudaliar.kitchtally.domain.Entry
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Life
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import com.rachitratnammudaliar.kitchtally.domain.RecipeLine
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Something you keep at home: rice, bread, shampoo. */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "Other",
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
    /** How many units one pack holds, e.g. 10 eggs; 0 if it isn't bought in packs. */
    val packSize: Double = 0.0,
    // Added in database version 3 (app 1.2.0).
    /** Not sure whether to buy it again: never put on the shopping list until switched off. */
    val unsure: Boolean = false,
    // Added in database version 4 (app 1.3.0).
    /** Show how many meals are left. Off for salt, oil and the like. */
    val countMeals: Boolean = false,
    /** Meals one unit makes (one full packet for % items). 0 until set. */
    val mealsPerUnit: Double = 0.0,
    /** [lowAt] is counted in meals. */
    val lowInMeals: Boolean = false,
    // Added in database version 6 (app 1.7.0).
    /** No need to buy again: never on the shopping list, shown in its own group. */
    val noRebuy: Boolean = false,
    // Added in database version 7 (app 1.8.0).
    /** Where it sits on To buy: 0 ASAP, 1 Priority (usual), 2 Anytime. Back to 1 once bought. */
    val buyPriority: Int = 1,
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
    /** For BOUGHT: the day this purchase starts counting on the Weekly tab (a shop for next week). Null = the day it happened. */
    val countsFromDay: Long? = null,
    // Added in database version 6 (app 1.7.0).
    /** Bought on a craving: an avoidable expense. */
    val craving: Boolean = false,
)

fun Item.unitValue(): QtyUnit = QtyUnit.fromName(unit)

/** How this item is counted, for the stock rules. */
fun Item.counting(): Counting {
    val meals = if (countMeals) mealsPerUnit else 0.0
    return Counting(unitValue(), lowAt, meals, lowInMeals && meals > 0)
}

fun Item.lifeValue(): Life = Life.fromName(life)

fun Event.typeValue(): EventType = EventType.fromName(type)

fun Event.date(): LocalDate = LocalDate.ofEpochDay(epochDay)

fun Event.toEntry(): Entry = Entry(typeValue(), date(), quantity, priceMinor, shop, countsFromDay?.let { LocalDate.ofEpochDay(it) }, craving)

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

    /** Marking an item as needed also means you're sure about buying it again. */
    @Query("UPDATE items SET refillNeeded = :needed, unsure = CASE WHEN :needed THEN 0 ELSE unsure END, noRebuy = CASE WHEN :needed THEN 0 ELSE noRebuy END WHERE id = :id")
    suspend fun setRefill(id: Long, needed: Boolean)

    @Query("UPDATE items SET unsure = :unsure, refillNeeded = CASE WHEN :unsure THEN 0 ELSE refillNeeded END, noRebuy = CASE WHEN :unsure THEN 0 ELSE noRebuy END WHERE id = :id")
    suspend fun setUnsure(id: Long, unsure: Boolean)

    @Query("UPDATE items SET noRebuy = :on, refillNeeded = CASE WHEN :on THEN 0 ELSE refillNeeded END, unsure = CASE WHEN :on THEN 0 ELSE unsure END WHERE id = :id")
    suspend fun setNoRebuy(id: Long, on: Boolean)

    @Query("SELECT * FROM recipes ORDER BY name COLLATE NOCASE")
    fun observeRecipes(): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes")
    suspend fun recipes(): List<Recipe>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun recipe(id: Long): Recipe?

    @Insert
    suspend fun insert(recipe: Recipe): Long

    @Update
    suspend fun update(recipe: Recipe)

    @Delete
    suspend fun delete(recipe: Recipe)

    @Query("UPDATE recipes SET plannedDay = :day WHERE id = :id")
    suspend fun setPlanned(id: Long, day: Long)

    @Query("UPDATE items SET buyPriority = :priority WHERE id = :id")
    suspend fun setBuyPriority(id: Long, priority: Int)

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

    /** Moves purchases to count from [day] on the Weekly tab, or back to the day they happened (null). */
    @Query("UPDATE events SET countsFromDay = :day WHERE id IN (:ids)")
    suspend fun setCountsFrom(ids: List<Long>, day: Long?)

    /** After buying: off the manual list, and shown again if it was hidden. */
    @Query("UPDATE items SET refillNeeded = 0, hiddenFromList = 0, buyPriority = 1 WHERE id = :id")
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
 * pack size, and free-text shopping notes. Kilos and litres start at half a unit per tap.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN step REAL NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE items ADD COLUMN hiddenFromList INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN packSize REAL NOT NULL DEFAULT 0")
        db.execSQL("UPDATE items SET step = 0.5 WHERE unit IN ('KG', 'LITRE')")
        db.execSQL("CREATE TABLE IF NOT EXISTS shopping_notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }
}

/** Version 2 -> 3 (app 1.1.0 -> 1.2.0): "not sure to buy again", and the new category names. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN unsure INTEGER NOT NULL DEFAULT 0")
        // Categories renamed in 1.2.0.
        Categories.renamed.forEach { (old, new) ->
            db.execSQL("UPDATE items SET category = ? WHERE category = ?", arrayOf<Any>(new, old))
        }
    }
}

/** Version 3 -> 4 (app 1.2 -> 1.3.0): counting meals, and "running low at" in meals. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN countMeals INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN mealsPerUnit REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN lowInMeals INTEGER NOT NULL DEFAULT 0")
    }
}

/** Version 4 -> 5 (app 1.3 -> 1.4.0): a purchase can count for next week. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN countsFromDay INTEGER")
    }
}

/** Version 5 -> 6 (app 1.6 -> 1.7.0): bought-on-a-craving purchases, and "no need to buy again" items. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN craving INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN noRebuy INTEGER NOT NULL DEFAULT 0")
    }
}

/** A dish: ingredients picked from the pantry, how many meals one cook makes, and when it's planned. */
@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Meals from one cook when you eat it yourself. */
    val mealsAlone: Int = 4,
    /** Meals from one cook when you share it. */
    val mealsShared: Int = 2,
    /** The ingredients as JSON: [{"i":itemId,"n":needed,"a":amount}]. */
    val lines: String = "[]",
    /** -1 not planned, 0 some day this week, otherwise the epoch day it's planned for. */
    val plannedDay: Long = -1,
)

fun Recipe.lineList(): List<RecipeLine> = runCatching {
    val array = org.json.JSONArray(lines)
    (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        RecipeLine(o.getLong("i"), o.optBoolean("n", true), o.optDouble("a", 0.0))
    }
}.getOrDefault(emptyList())

fun encodeLines(lines: List<RecipeLine>): String =
    org.json.JSONArray(lines.map { org.json.JSONObject().put("i", it.itemId).put("n", it.needed).put("a", it.amount) }).toString()

/** Version 6 -> 7 (app 1.7 -> 1.8.0): recipes, the meal plan, and ASAP / Priority / Anytime on To buy. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recipes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, mealsAlone INTEGER NOT NULL, mealsShared INTEGER NOT NULL, lines TEXT NOT NULL, plannedDay INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE items ADD COLUMN buyPriority INTEGER NOT NULL DEFAULT 1")
    }
}

@Database(entities = [Item::class, Event::class, ShoppingNote::class, Recipe::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pantry(): PantryDao

    companion object {
        const val DATABASE_NAME = "kitchtally.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build().also { instance = it }
            }
    }
}
