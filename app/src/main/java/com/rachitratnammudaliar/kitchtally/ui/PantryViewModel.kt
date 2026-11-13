@file:OptIn(kotlinx.coroutines.FlowPreview::class)

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
import com.rachitratnammudaliar.kitchtally.data.Recipe
import com.rachitratnammudaliar.kitchtally.data.encodeLines
import com.rachitratnammudaliar.kitchtally.data.lineList
import com.rachitratnammudaliar.kitchtally.domain.RecipeLine
import com.rachitratnammudaliar.kitchtally.domain.Recipes
import com.rachitratnammudaliar.kitchtally.data.Prefs
import com.rachitratnammudaliar.kitchtally.data.ShoppingNote
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.data.toEntry
import com.rachitratnammudaliar.kitchtally.data.typeValue
import com.rachitratnammudaliar.kitchtally.domain.Entry
import com.rachitratnammudaliar.kitchtally.widget.ShoppingWidget
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Categories
import com.rachitratnammudaliar.kitchtally.domain.Life
import com.rachitratnammudaliar.kitchtally.domain.Meals
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.Qty
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

/** Everything the screens show, kept together so stock and lists always agree. */
data class PantryState(
    val items: List<Item>,
    val events: List<Event>,
    val notes: List<ShoppingNote> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
) {
    val eventsByItem: Map<Long, List<Event>> = events.groupBy { it.itemId }
    val entriesByItem: Map<Long, List<Entry>> = eventsByItem.mapValues { (_, l) -> l.map { it.toEntry() } }

    fun entries(id: Long): List<Entry> = entriesByItem[id].orEmpty()
    fun events(id: Long): List<Event> = eventsByItem[id].orEmpty()
    fun item(id: Long): Item? = items.firstOrNull { it.id == id }
    fun stock(id: Long): Double = Pantry.stock(entries(id))

    /** Face cream, toilet paper, shower gel and the like are counted in Budget, not in the weekly shop. */
    fun isLong(id: Long): Boolean = item(id)?.category?.let { Categories.isLongLasting(it) } == true

    /** Shop names typed before, newest first, for the shop buttons. */
    val usedShops: List<String> = events.sortedByDescending { it.createdAt }.map { it.shop }.filter { it.isNotBlank() }
    val usedCategories: List<String> = items.map { it.category }
}

class PantryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.pantry()
    private val prefs = Prefs(app)

    val state: StateFlow<PantryState?> = combine(dao.observeItems(), dao.observeEvents(), dao.observeNotes(), dao.observeRecipes()) { items, events, notes, recipes ->
        PantryState(items, events, notes, recipes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Quick taps are handled one at a time, so stock can't go below zero. */
    private val tapLock = Mutex()

    private val _currency = MutableStateFlow(prefs.currency)
    val currency: StateFlow<String> = _currency.asStateFlow()

    fun setCurrency(code: String) {
        prefs.currency = code
        _currency.value = code
    }

    private val _showSpending = MutableStateFlow(prefs.showSpending)
    val showSpending: StateFlow<Boolean> = _showSpending.asStateFlow()

    fun setShowSpending(show: Boolean) {
        prefs.showSpending = show
        _showSpending.value = show
    }

    private val _weekStartDay = MutableStateFlow(prefs.weekStartDay)
    val weekStartDay: StateFlow<Int> = _weekStartDay.asStateFlow()

    fun setWeekStartDay(day: Int) {
        prefs.weekStartDay = day
        _weekStartDay.value = prefs.weekStartDay
        // Choosing a first day starts the weeks over from that day.
        setWeekAnchor(0L)
        // The old switch was worked out for the old week.
        setForNextWeek(false)
    }

    private val _forNextWeekUntil = MutableStateFlow(prefs.forNextWeekUntil)
    /** Epoch day the "shopping for next week" switch lasts until; it is on while today is before it. */
    val forNextWeekUntil: StateFlow<Long> = _forNextWeekUntil.asStateFlow()

    fun setForNextWeek(on: Boolean) {
        val until = if (on) Pantry.weekStart(LocalDate.now(), DayOfWeek.of(prefs.weekStartDay)).plusWeeks(1).toEpochDay() else 0L
        prefs.forNextWeekUntil = until
        _forNextWeekUntil.value = until
    }

    private val _weekAnchorDay = MutableStateFlow(prefs.weekAnchorDay)
    /** The day "Start new week" was pressed (epoch day), or 0. */
    val weekAnchorDay: StateFlow<Long> = _weekAnchorDay.asStateFlow()

    /** Today becomes day one of a new week. Returns the old anchor, for Undo. */
    fun startNewWeek(today: LocalDate): Long {
        val before = prefs.weekAnchorDay
        setWeekAnchor(today.toEpochDay())
        return before
    }

    fun setWeekAnchor(day: Long) {
        prefs.weekAnchorDay = day
        _weekAnchorDay.value = day
    }

    /** Where a purchase made right now should start counting, or null for this week. */
    private fun buyCountsFrom(): Long? = prefs.forNextWeekUntil.takeIf { it > LocalDate.now().toEpochDay() }

    private val _budgetMinor = MutableStateFlow(prefs.budgetMinor)
    val budgetMinor: StateFlow<Long> = _budgetMinor.asStateFlow()

    fun setBudget(minor: Long) {
        prefs.budgetMinor = minor
        _budgetMinor.value = prefs.budgetMinor
    }

    init {
        // Keep the home screen widget in step with every change.
        viewModelScope.launch {
            combine(dao.observeItems(), dao.observeEvents(), dao.observeNotes()) { _, _, _ -> Unit }
                .debounce(500)
                .collect { ShoppingWidget.refresh(getApplication()) }
        }
    }

    /**
     * Cook: one meal from each chosen item (or one tap's worth if it doesn't count meals), logged
     * together and undone together. Never takes more than is left.
     */
    fun cook(items: List<Item>, shared: Boolean, onDone: (message: String, undo: () -> Unit) -> Unit) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val ids = tapLock.withLock {
                db.withTransaction {
                    items.mapNotNull { item ->
                        val amount = Pantry.useAmount(Meals.cookAmount(item.step, if (item.countMeals) item.mealsPerUnit else 0.0), dao.stockOf(item.id))
                        if (amount <= 0) return@mapNotNull null
                        val type = if (shared) EventType.SHARED else EventType.USED
                        dao.insert(Event(itemId = item.id, type = type.name, epochDay = LocalDate.now().toEpochDay(), quantity = amount, note = "Cooked"))
                    }
                }
            }
            if (ids.isEmpty()) return@launch
            val names = items.joinToString(", ") { it.name }
            onDone("Cooked with $names") { viewModelScope.launch { db.withTransaction { ids.forEach { dao.deleteEvent(it) } } } }
        }
    }

    /** "Done shopping": everything ticked is bought at once, at last time's amount, shop and price. */
    fun doneShopping(state: PantryState, items: List<Item>, onDone: (message: String, undo: () -> Unit) -> Unit) {
        if (items.isEmpty()) return
        val today = LocalDate.now().toEpochDay()
        viewModelScope.launch {
            val buys = tapLock.withLock {
                db.withTransaction {
                    items.map { item ->
                        val entries = state.entries(item.id)
                        val amount = Pantry.buyAmount(item.unitValue(), entries, item.packSize)
                        val shop = Pantry.lastPriced(entries)?.shop?.takeIf { it.isNotBlank() } ?: Pantry.lastBought(entries)?.shop ?: ""
                        val id = dao.insert(
                            Event(itemId = item.id, type = EventType.BOUGHT.name, epochDay = today, quantity = amount, priceMinor = Pantry.expectedPriceMinor(item.unitValue(), entries, item.packSize), shop = shop, countsFromDay = buyCountsFrom()),
                        )
                        dao.clearRefill(item.id)
                        QuickBuy(id, amount, item.id, item.refillNeeded, item.hiddenFromList)
                    }
                }
            }
            onDone("Bought ${buys.size} item${if (buys.size == 1) "" else "s"}") { buys.forEach { undoBuy(it) } }
        }
    }

    /** From the purchase screen: "these make about 8 meals" sets meals per unit. */
    fun setMealsPerUnit(itemId: Long, mealsPerUnit: Double) {
        viewModelScope.launch {
            val item = dao.item(itemId) ?: return@launch
            dao.update(item.copy(countMeals = true, mealsPerUnit = mealsPerUnit))
        }
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

    fun setBuyPriority(item: Item, priority: Int) {
        viewModelScope.launch { dao.setBuyPriority(item.id, priority.coerceIn(0, 2)) }
    }

    fun saveRecipe(recipe: Recipe, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            if (recipe.id == 0L) dao.insert(recipe) else dao.update(recipe)
            onSaved()
        }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { dao.delete(recipe) }
    }

    fun setPlan(recipe: Recipe, day: Long) {
        viewModelScope.launch { dao.setPlanned(recipe.id, day) }
    }

    /** Marks the missing ingredients as needing a refill, so they show on To buy. */
    fun addMissingToBuy(items: List<Item>) {
        viewModelScope.launch { items.forEach { dao.setRefill(it.id, true) } }
    }

    /**
     * Cook a recipe: every ingredient goes down by the amount given (never below zero), solo or shared.
     * A planned meal is ticked off. Undo puts it all back.
     */
    fun cookRecipe(recipe: Recipe, amounts: List<Pair<Item, Double>>, shared: Boolean, onDone: (message: String, undo: () -> Unit) -> Unit) {
        viewModelScope.launch {
            val ids = tapLock.withLock {
                db.withTransaction {
                    amounts.mapNotNull { (item, wanted) ->
                        val amount = Qty.round(minOf(wanted, dao.stockOf(item.id)))
                        if (amount <= 0) return@mapNotNull null
                        val type = if (shared) EventType.SHARED else EventType.USED
                        dao.insert(Event(itemId = item.id, type = type.name, epochDay = LocalDate.now().toEpochDay(), quantity = amount, note = "Cooked ${recipe.name}".take(80)))
                    }
                }
            }
            dao.setPlanned(recipe.id, Recipes.NOT_PLANNED)
            val meals = if (shared) recipe.mealsShared else recipe.mealsAlone
            onDone("Cooked ${recipe.name} · $meals ${if (meals == 1) "meal" else "meals"}${if (shared) ", shared" else ""}") {
                viewModelScope.launch {
                    db.withTransaction { ids.forEach { dao.deleteEvent(it) } }
                    dao.setPlanned(recipe.id, recipe.plannedDay)
                }
            }
        }
    }

    fun setRefillNeeded(item: Item, needed: Boolean) {
        viewModelScope.launch { dao.setRefill(item.id, needed) }
    }

    fun setNoRebuy(item: Item, on: Boolean) {
        viewModelScope.launch { dao.setNoRebuy(item.id, on) }
    }

    fun setUnsure(item: Item, unsure: Boolean) {
        viewModelScope.launch { dao.setUnsure(item.id, unsure) }
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
                    Event(itemId = item.id, type = EventType.BOUGHT.name, epochDay = LocalDate.now().toEpochDay(), quantity = amount, priceMinor = price, shop = shop, countsFromDay = buyCountsFrom()),
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
                            Event(itemId = item.id, type = EventType.BOUGHT.name, epochDay = today, quantity = amount, priceMinor = Pantry.priceFor(entries, amount), shop = shop, countsFromDay = buyCountsFrom()),
                        )
                        dao.clearRefill(item.id)
                        onDone("Added ${unit.label(amount)}") { undoBuy(QuickBuy(id, amount, item.id, item.refillNeeded, item.hiddenFromList)) }
                    }
                    Adjust.SET -> {
                        val diff = Qty.round(amount - stock)
                        if (diff == 0.0) return@withLock
                        val id = dao.insert(Event(itemId = item.id, type = EventType.ADJUST.name, epochDay = today, quantity = diff, note = "Counted"))
                        // Topping the stock up means it no longer needs buying.
                        val restocked = diff > 0 && item.refillNeeded
                        if (restocked) dao.clearRefill(item.id)
                        onDone("Set to ${unit.label(amount)}") {
                            undo(id)
                            if (restocked) viewModelScope.launch { dao.item(item.id)?.let { dao.update(it.copy(refillNeeded = true)) } }
                        }
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
                dao.update((dao.item(match.id) ?: match).copy(refillNeeded = true, hiddenFromList = false, unsure = false, noRebuy = false))
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
                Event(itemId = itemId, type = type.name, epochDay = LocalDate.now().toEpochDay(), quantity = quantity, priceMinor = priceMinor, shop = shop, countsFromDay = if (type == EventType.BOUGHT) buyCountsFrom() else null),
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
        val text = Backup.encode(items, dao.events(), dao.notes(), dao.recipes())
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("Couldn't write the file")
        items.size
    }

    suspend fun importFrom(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Couldn't read the file")
        val contents = Backup.decode(text)
        db.withTransaction {
            val newIds = mutableMapOf<Long, Long>()
            contents.items.forEach { item ->
                val events = contents.events.filter { it.itemId == item.id }
                newIds[item.id] = dao.insertItemWithEvents(item.copy(id = 0), events)
            }
            contents.notes.forEach { dao.insert(it.copy(id = 0)) }
            contents.recipes.forEach { r ->
                val lines = r.lineList().mapNotNull { l -> newIds[l.itemId]?.let { l.copy(itemId = it) } }
                dao.insert(r.copy(id = 0, lines = encodeLines(lines)))
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
            suspend fun add(
                name: String, category: String, unit: QtyUnit, life: Life, vararg events: Event,
                lowAt: Double = Pantry.defaultLowAt(unit), refill: Boolean = false, meals: Double = 0.0, pack: Double = 0.0,
                step: Double = Pantry.defaultStep(unit), unsure: Boolean = false, lowInMeals: Boolean = false,
            ) {
                db.withTransaction {
                    dao.insertItemWithEvents(
                        Item(
                            name = name, category = category, unit = unit.name, life = life.name, lowAt = lowAt, refillNeeded = refill, step = step,
                            packSize = pack, countMeals = meals > 0, mealsPerUnit = meals, unsure = unsure, lowInMeals = lowInMeals,
                        ),
                        events.toList(),
                    )
                }
            }
            add("Bread", "Bread & bakery", QtyUnit.LEVEL, Life.SHORT,
                ev(b, 6, 1.0, 129, "IKI"), ev(u, 5, 0.25), ev(u, 4, 0.25), ev(u, 3, 0.5), ev(b, 2, 1.0, 129, "IKI"), ev(u, 1, 0.25), ev(u, 0, 0.5))
            add("Milk", "Dairy & eggs", QtyUnit.LEVEL, Life.SHORT,
                ev(b, 3, 1.0, 99, "Maxima"), ev(u, 2, 0.25), ev(u, 0, 0.25))
            add("Face cream", "Personal care", QtyUnit.PIECE, Life.LONG,
                ev(b, 2, 1.0, 1290, "Maxima"), ev(u, 1, 0.25))
            add("Dishwashing liquid", "Household", QtyUnit.LEVEL, Life.LONG,
                ev(b, 3, 1.0, 199, "IKI"), ev(u, 0, 0.1))
            add("Basmati rice", "Staples", QtyUnit.KG, Life.LONG,
                ev(b, 20, 5.0, 1245, "Maxima"), ev(u, 15, 0.5), ev(u, 10, 0.5), ev(s, 4, 1.0), ev(u, 2, 0.5), meals = 6.0, step = 0.25)
            add("Atta", "Staples", QtyUnit.KG, Life.LONG,
                ev(b, 25, 5.0, 899, "Lidl"), ev(u, 18), ev(u, 9), ev(s, 4))
            add("Tomatoes", "Vegetables", QtyUnit.PIECE, Life.MEDIUM,
                ev(b, 6, 6.0, 249, "Lidl"), ev(u, 4, 2.0), ev(s, 2, 1.0), meals = 0.5)
            add("Red onion", "Vegetables", QtyUnit.PIECE, Life.MEDIUM,
                ev(b, 9, 5.0, 138, "Norfa"), ev(b, 2, 5.0, 129, "Lidl"), ev(u, 6, 2.0), ev(u, 1, 3.0), meals = 1.6)
            add("Spaghetti", "Staples", QtyUnit.PACKET, Life.LONG,
                ev(b, 16, 2.0, 158, "Rimi"), ev(u, 12, 0.5), ev(s, 5, 0.5), meals = 4.0, step = 0.25)
            add("Eggs", "Dairy & eggs", QtyUnit.PIECE, Life.MEDIUM,
                ev(b, 5, 10.0, 289, "IKI"), ev(u, 3, 2.0), ev(s, 1, 2.0), lowAt = 3.0, pack = 10.0, meals = 0.5, step = 2.0)
            add("Chicken", "Meat & protein", QtyUnit.PACKET, Life.SHORT,
                ev(b, 4, 1.0, 449, "Maxima"), ev(u, 2, 0.34), ev(s, 1, 0.33), meals = 3.0, lowAt = 2.0, lowInMeals = true)
            add("Sunflower oil", "Staples", QtyUnit.LEVEL, Life.LONG,
                ev(b, 30, 1.0, 239, "Lidl"), ev(u, 20, 0.4), ev(u, 10, 0.4), ev(u, 2, 0.1), lowAt = 0.1, step = 0.05)
            add("Frozen veggie mix", "Vegetables", QtyUnit.LEVEL, Life.MEDIUM,
                ev(b, 3, 1.0, 199, "IKI"), ev(u, 1, 0.2), meals = 4.0)
            add("Body wash", "Personal care", QtyUnit.LEVEL, Life.LONG, ev(b, 30, 1.0, 199, "Pepco"), ev(u, 10, 0.5), refill = true)
            add("Dishwashing liquid", "Household", QtyUnit.LEVEL, Life.LONG, ev(b, 40, 1.0, 149, "Maxima"), ev(u, 1, 0.25))
            add("Instant noodles", "Staples", QtyUnit.PACKET, Life.LONG,
                ev(b, 20, 5.0, 295, "Maxima"), ev(u, 15, 2.0), ev(u, 8, 2.0), ev(u, 3), unsure = true)
            dao.insert(ShoppingNote(text = "Birthday candles"))
            dao.insert(ShoppingNote(text = "Aluminium foil"))
        }
    }
}

