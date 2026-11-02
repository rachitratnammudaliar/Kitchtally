package com.rachitratnammudaliar.kitchtally.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.rachitratnammudaliar.kitchtally.backup.Backup
import com.rachitratnammudaliar.kitchtally.data.AppDatabase
import com.rachitratnammudaliar.kitchtally.data.Event
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.PhotoStore
import com.rachitratnammudaliar.kitchtally.data.Prefs
import com.rachitratnammudaliar.kitchtally.data.toEntry
import com.rachitratnammudaliar.kitchtally.data.typeValue
import com.rachitratnammudaliar.kitchtally.domain.Entry
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Life
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Everything the screens show, kept together so stock and lists always agree. */
data class PantryState(
    val items: List<Item>,
    val events: List<Event>,
) {
    val eventsByItem: Map<Long, List<Event>> = events.groupBy { it.itemId }
    val entriesByItem: Map<Long, List<Entry>> = eventsByItem.mapValues { (_, l) -> l.map { it.toEntry() } }

    fun entries(id: Long): List<Entry> = entriesByItem[id].orEmpty()
    fun events(id: Long): List<Event> = eventsByItem[id].orEmpty()
    fun item(id: Long): Item? = items.firstOrNull { it.id == id }
    fun stock(id: Long): Double = Pantry.stock(entries(id))

    /** Shop names typed before, newest first, for the shop buttons. */
    val usedShops: List<String> = events.sortedByDescending { it.createdAt }.map { it.shop }.filter { it.isNotBlank() }
    val usedCategories: List<String> = items.map { it.category }
}

class PantryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.pantry()
    private val prefs = Prefs(app)

    val state: StateFlow<PantryState?> = combine(dao.observeItems(), dao.observeEvents()) { items, events ->
        PantryState(items, events)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _currency = MutableStateFlow(prefs.currency)
    val currency: StateFlow<String> = _currency.asStateFlow()

    fun setCurrency(code: String) {
        prefs.currency = code
        _currency.value = code
    }

    /** Saves an item. For a new one, [startStock] above zero is logged as the starting amount. */
    fun saveItem(item: Item, startStock: Double, replacedPhoto: String, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (item.id == 0L) {
                val start = if (startStock > 0) {
                    listOf(Event(itemId = 0, type = EventType.ADJUST.name, epochDay = LocalDate.now().toEpochDay(), quantity = startStock, note = "Starting stock"))
                } else {
                    emptyList()
                }
                db.withTransaction { dao.insertItemWithEvents(item, start) }
            } else {
                dao.update(item)
                item.id
            }
            if (replacedPhoto.isNotBlank() && replacedPhoto != item.photoFile) {
                withContext(Dispatchers.IO) { PhotoStore.delete(getApplication(), replacedPhoto) }
            }
            onSaved(id)
        }
    }

    fun deleteItem(item: Item, events: List<Event>) {
        viewModelScope.launch {
            dao.delete(item)
            withContext(Dispatchers.IO) {
                PhotoStore.delete(getApplication(), item.photoFile)
                events.forEach { PhotoStore.delete(getApplication(), it.receiptFile) }
            }
        }
    }

    fun setRefillNeeded(item: Item, needed: Boolean) {
        viewModelScope.launch { dao.update(item.copy(refillNeeded = needed)) }
    }

    /** Logs a quick change and returns its id through [onLogged], so it can be undone. */
    fun log(itemId: Long, type: EventType, quantity: Double = 1.0, priceMinor: Long? = null, shop: String = "", onLogged: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = dao.insert(
                Event(itemId = itemId, type = type.name, epochDay = LocalDate.now().toEpochDay(), quantity = quantity, priceMinor = priceMinor, shop = shop),
            )
            if (type == EventType.BOUGHT) dao.clearRefill(itemId)
            onLogged(id)
        }
    }

    /**
     * The quick "+" button: one more, today, at the same shop and unit price as last time.
     * Returns nothing; [onLogged] gets the new event's id for Undo.
     */
    fun quickBuy(state: PantryState, itemId: Long, onLogged: (Long) -> Unit) {
        val last = Pantry.lastPriced(state.entries(itemId))
        val lastShop = Pantry.lastBought(state.entries(itemId))?.shop ?: ""
        val price = last?.let { Pantry.unitPriceMinor(it) }?.let { Pantry.totalMinor(it, 1.0) }
        log(itemId, EventType.BOUGHT, 1.0, price, last?.shop ?: lastShop, onLogged)
    }

    fun saveEvent(event: Event, replacedReceipt: String) {
        viewModelScope.launch {
            if (event.id == 0L) dao.insert(event) else dao.update(event)
            if (event.typeValue() == EventType.BOUGHT) dao.clearRefill(event.itemId)
            if (replacedReceipt.isNotBlank() && replacedReceipt != event.receiptFile) {
                withContext(Dispatchers.IO) { PhotoStore.delete(getApplication(), replacedReceipt) }
            }
        }
    }

    fun deleteEvent(event: Event) {
        viewModelScope.launch {
            dao.deleteEvent(event.id)
            withContext(Dispatchers.IO) { PhotoStore.delete(getApplication(), event.receiptFile) }
        }
    }

    fun undo(eventId: Long) {
        viewModelScope.launch { dao.deleteEvent(eventId) }
    }

    suspend fun savePhoto(uri: Uri): String = withContext(Dispatchers.IO) { PhotoStore.save(getApplication(), uri) }

    fun discardPhoto(name: String) {
        viewModelScope.launch(Dispatchers.IO) { PhotoStore.delete(getApplication(), name) }
    }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val items = dao.items()
        val text = Backup.encode(items, dao.events())
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("Couldn't write the file")
        items.size
    }

    suspend fun importFrom(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Couldn't read the file")
        val contents = Backup.decode(text)
        db.withTransaction {
            contents.items.forEach { item ->
                val events = contents.events.filter { it.itemId == item.id }
                dao.insertItemWithEvents(item.copy(id = 0), events)
            }
        }
        contents.items.size
    }

    /** Sample data for screenshots (debug builds only). */
    fun seedDemo() {
        viewModelScope.launch {
            if (dao.count() > 0) return@launch
            val t = LocalDate.now()
            fun ev(type: EventType, daysAgo: Long, qty: Double = 1.0, price: Long? = null, shop: String = "") =
                Event(itemId = 0, type = type.name, epochDay = t.minusDays(daysAgo).toEpochDay(), quantity = qty, priceMinor = price, shop = shop)
            val b = EventType.BOUGHT
            val u = EventType.USED
            val s = EventType.SHARED
            suspend fun add(name: String, category: String, unit: QtyUnit, life: Life, vararg events: Event, lowAt: Double = 1.0, refill: Boolean = false) {
                db.withTransaction {
                    dao.insertItemWithEvents(Item(name = name, category = category, unit = unit.name, life = life.name, lowAt = lowAt, refillNeeded = refill), events.toList())
                }
            }
            add("Bread", "Bread & bakery", QtyUnit.PACKET, Life.SHORT,
                ev(b, 12, 2.0, 178, "IKI"), ev(u, 11), ev(u, 9), ev(b, 7, 2.0, 178, "IKI"), ev(u, 6), ev(s, 4), ev(b, 3, 2.0, 178, "Norfa"), ev(u, 1), ev(b, 0, 2.0, 178, "IKI"), ev(u, 0), ev(s, 0))
            add("Milk", "Dairy & eggs", QtyUnit.BOTTLE, Life.SHORT,
                ev(b, 9, 2.0, 198, "Maxima"), ev(u, 7), ev(u, 3), ev(b, 2, 1.0, 99, "IKI"), ev(u, 1), ev(b, 0, 2.0, 198, "IKI"), ev(u, 0))
            add("Basmati rice", "Grains & flour", QtyUnit.KG, Life.LONG,
                ev(b, 20, 5.0, 1245, "Maxima"), ev(u, 15, 0.5), ev(u, 10, 0.5), ev(s, 4, 1.0), ev(u, 2, 0.5))
            add("Atta (wheat flour)", "Grains & flour", QtyUnit.KG, Life.LONG,
                ev(b, 25, 5.0, 899, "Lidl"), ev(u, 18), ev(u, 9), ev(s, 4))
            add("Tomatoes", "Fruit & veg", QtyUnit.KG, Life.SHORT,
                ev(b, 6, 1.0, 249, "Lidl"), ev(u, 4, 0.5), ev(s, 2, 0.5), ev(b, 0, 1.0, 239, "Lidl"), ev(s, 0, 0.5))
            add("Onions", "Fruit & veg", QtyUnit.KG, Life.MEDIUM,
                ev(b, 14, 2.0, 138, "Norfa"), ev(u, 10, 0.5), ev(u, 6, 0.5), ev(u, 2, 0.5))
            add("Spaghetti", "Groceries", QtyUnit.PACKET, Life.LONG,
                ev(b, 16, 3.0, 237, "Rimi"), ev(u, 12), ev(s, 5))
            add("Eggs", "Dairy & eggs", QtyUnit.BOX, Life.MEDIUM, ev(b, 5, 1.0, 289, "IKI"))
            add("Toothpaste", "Toiletries", QtyUnit.PIECE, Life.LONG, ev(b, 30, 1.0, 199, "Pepco"), refill = true)
            add("Dish soap", "Cleaning", QtyUnit.BOTTLE, Life.LONG, ev(b, 40, 1.0, 149, "Maxima"), ev(u, 1))
        }
    }
}

