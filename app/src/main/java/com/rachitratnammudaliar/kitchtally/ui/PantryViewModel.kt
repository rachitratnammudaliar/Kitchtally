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
import com.rachitratnammudaliar.kitchtally.data.ShoppingNote
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.data.toEntry
import com.rachitratnammudaliar.kitchtally.data.typeValue
import com.rachitratnammudaliar.kitchtally.domain.Entry
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Life
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.Qty
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Everything the screens show, kept together so stock and lists always agree. */
data class PantryState(
    val items: List<Item>,
    val events: List<Event>,
    val notes: List<ShoppingNote> = emptyList(),
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

    val state: StateFlow<PantryState?> = combine(dao.observeItems(), dao.observeEvents(), dao.observeNotes()) { items, events, notes ->
        PantryState(items, events, notes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Quick taps are handled one at a time, so stock can't go below zero. */
    private val tapLock = Mutex()

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
        viewModelScope.launch { dao.setRefill(item.id, needed) }
    }

    /** Hides an item from the shopping list until it's bought again, or shows it again. */
    fun setHidden(item: Item, hidden: Boolean) {
        viewModelScope.launch {
            dao.update((dao.item(item.id) ?: return@launch).copy(hiddenFromList = hidden, refillNeeded = if (hidden) false else item.refillNeeded))
        }
    }

    /**
     * − (used) or Shared: takes the item's "uses per tap" amount, never more than is left.
     * [onLogged] gets the event id and the amount, for the Undo message. Nothing happens at zero.
     */
    fun use(item: Item, shared: Boolean, onLogged: (eventId: Long, amount: Double) -> Unit) {
        viewModelScope.launch {
            tapLock.withLock {
                val amount = Pantry.useAmount(item.step, dao.stockOf(item.id))
                if (amount <= 0) return@withLock
                val type = if (shared) EventType.SHARED else EventType.USED
                val id = dao.insert(Event(itemId = item.id, type = type.name, epochDay = LocalDate.now().toEpochDay(), quantity = amount))
                onLogged(id, amount)
            }
        }
    }

    /** What a quick ＋ changed, so Undo can put everything back. */
    data class QuickBuy(val eventId: Long, val amount: Double, val itemId: Long, val wasRefill: Boolean, val wasHidden: Boolean)

    /**
     * The quick ＋: one more (or your usual pack size for kilos and litres), today, at last
     * time's shop and price.
     */
    fun quickBuy(state: PantryState, item: Item, onLogged: (QuickBuy) -> Unit) {
        val entries = state.entries(item.id)
        val amount = Pantry.buyAmount(item.unitValue(), entries, item.packSize)
        val price = Pantry.expectedPriceMinor(item.unitValue(), entries, item.packSize)
        val shop = Pantry.lastPriced(entries)?.shop?.takeIf { it.isNotBlank() } ?: Pantry.lastBought(entries)?.shop ?: ""
        viewModelScope.launch {
            tapLock.withLock {
                val id = dao.insert(
                    Event(itemId = item.id, type = EventType.BOUGHT.name, epochDay = LocalDate.now().toEpochDay(), quantity = amount, priceMinor = price, shop = shop),
                )
                dao.clearRefill(item.id)
                onLogged(QuickBuy(id, amount, item.id, item.refillNeeded, item.hiddenFromList))
            }
        }
    }

    enum class Adjust { USED, SHARED, BOUGHT, SET }

    /**
     * The adjust box: use, share or buy any amount, or set the stock to an exact amount.
     * Uses never go below zero. [onDone] gets an Undo action and a message, or nothing if nothing changed.
     */
    fun adjust(state: PantryState, item: Item, action: Adjust, amount: Double, onDone: (message: String, undo: () -> Unit) -> Unit) {
        val unit = item.unitValue()
        val entries = state.entries(item.id)
        viewModelScope.launch {
            tapLock.withLock {
                val stock = dao.stockOf(item.id)
                val today = LocalDate.now().toEpochDay()
                when (action) {
                    Adjust.USED, Adjust.SHARED -> {
                        val q = Qty.round(minOf(amount, stock))
                        if (q <= 0) return@withLock
                        val type = if (action == Adjust.SHARED) EventType.SHARED else EventType.USED
                        val id = dao.insert(Event(itemId = item.id, type = type.name, epochDay = today, quantity = q))
                        onDone("${if (action == Adjust.SHARED) "Shared" else "Used"} ${unit.label(q)}") { undo(id) }
                    }
                    Adjust.BOUGHT -> {
                        if (amount <= 0) return@withLock
                        val shop = Pantry.lastPriced(entries)?.shop?.takeIf { it.isNotBlank() } ?: Pantry.lastBought(entries)?.shop ?: ""
                        val id = dao.insert(
                            Event(itemId = item.id, type = EventType.BOUGHT.name, epochDay = today, quantity = amount, priceMinor = Pantry.priceFor(entries, amount), shop = shop),
                        )
                        dao.clearRefill(item.id)
                        onDone("Added ${unit.label(amount)}") { undoBuy(QuickBuy(id, amount, item.id, item.refillNeeded, item.hiddenFromList)) }
                    }
                    Adjust.SET -> {
                        val diff = Qty.round(amount - stock)
                        if (diff == 0.0) return@withLock
                        val id = dao.insert(Event(itemId = item.id, type = EventType.ADJUST.name, epochDay = today, quantity = diff, note = "Counted"))
                        onDone("Set to ${unit.label(amount)}") { undo(id) }
                    }
                }
            }
        }
    }

    fun undoBuy(buy: QuickBuy) {
        viewModelScope.launch {
            dao.deleteEvent(buy.eventId)
            val current = dao.item(buy.itemId) ?: return@launch
            dao.update(current.copy(refillNeeded = buy.wasRefill, hiddenFromList = buy.wasHidden))
        }
    }

    /**
     * Adds a line to the shopping list. If it matches a pantry item's name, that item is marked
     * "refill needed" instead, so it isn't listed twice. Returns through [onAdded] whether a
     * pantry item was used.
     */
    fun addNote(text: String, state: PantryState, onAdded: (matchedItem: Boolean) -> Unit = {}) {
        val clean = text.trim().take(80)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val match = state.items.firstOrNull { it.name.equals(clean, ignoreCase = true) }
            if (match != null) {
                dao.update((dao.item(match.id) ?: match).copy(refillNeeded = true, hiddenFromList = false))
            } else {
                dao.insert(ShoppingNote(text = clean))
            }
            onAdded(match != null)
        }
    }

    fun deleteNote(note: ShoppingNote) {
        viewModelScope.launch { dao.deleteNote(note.id) }
    }

    fun restoreNote(note: ShoppingNote) {
        viewModelScope.launch { dao.insert(note.copy(id = 0)) }
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
        val text = Backup.encode(items, dao.events(), dao.notes())
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
            contents.notes.forEach { dao.insert(it.copy(id = 0)) }
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
                    dao.insertItemWithEvents(
                        Item(name = name, category = category, unit = unit.name, life = life.name, lowAt = lowAt, refillNeeded = refill, step = Pantry.defaultStep(unit)),
                        events.toList(),
                    )
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
            db.withTransaction {
                dao.insertItemWithEvents(
                    Item(name = "Eggs", category = "Dairy & eggs", unit = QtyUnit.PIECE.name, life = Life.MEDIUM.name, lowAt = 3.0, packSize = 10.0),
                    listOf(ev(b, 5, 10.0, 289, "IKI"), ev(u, 3, 2.0), ev(s, 1, 2.0)),
                )
            }
            add("Toothpaste", "Toiletries", QtyUnit.PIECE, Life.LONG, ev(b, 30, 1.0, 199, "Pepco"), refill = true)
            add("Dish soap", "Cleaning", QtyUnit.BOTTLE, Life.LONG, ev(b, 40, 1.0, 149, "Maxima"), ev(u, 1))
            dao.insert(ShoppingNote(text = "Birthday candles"))
            dao.insert(ShoppingNote(text = "Aluminium foil"))
        }
    }
}

