@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.kitchtally.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.lifeValue
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Categories
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Money
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.Qty
import com.rachitratnammudaliar.kitchtally.domain.StockStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/* ============================ Presentation ============================ */

/** "2 packets left", "Out", "0.5 kg left". */
fun stockLabel(item: Item, stock: Double): String =
    if (stock <= 0) "Out" else "${item.unitValue().label(stock)} left"

/** The second line under an item: freshness, days left, or when it was last bought. */
fun detailLine(item: Item, state: PantryState, today: LocalDate): String {
    val entries = state.entries(item.id)
    val last = Pantry.lastBought(entries)
    if (Pantry.checkFreshness(entries, item.lifeValue(), today) && last != null) {
        return "Bought ${Pantry.daysAgoLabel(last.date, today)} · check it's fresh"
    }
    val left = Pantry.daysLeft(entries, today)
    if (left != null && Pantry.stock(entries) > 0) {
        return when (left) {
            0L -> "Likely runs out today"
            1L -> "About 1 day left"
            else -> "About $left days left"
        }
    }
    if (last != null) return "Bought ${Pantry.daysAgoLabel(last.date, today)}" + if (last.shop.isNotBlank()) " at ${last.shop}" else ""
    return "Not bought yet"
}

/** On the list when marked, out or running low, unless hidden until it's bought again. */
fun onShoppingList(item: Item, state: PantryState, today: LocalDate): Boolean =
    !item.unsure && (item.refillNeeded || (!item.hiddenFromList && Pantry.status(state.entries(item.id), item.lowAt, today) != StockStatus.OK))

const val UNSURE_GROUP = "🤔  Not sure to buy again"

/** Preset categories in order, then the user's own alphabetically. */
fun orderedCategories(used: List<String>): List<String> {
    val custom = used.filter { u -> Categories.presets.none { it.equals(u, ignoreCase = true) } }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    return Categories.presets.filter { p -> used.any { it.equals(p, ignoreCase = true) } } + custom
}

/** Shows an Undo snackbar for a quick action. */
fun CoroutineScope.undoSnackbar(host: SnackbarHostState, message: String, onUndo: () -> Unit) {
    launch {
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }
}

/* ============================ Navigation ============================ */

/**
 * @param startScreen "pantry", "shopping", "week", "settings", "item:ID", "edit:ID", "buy:ITEM" or
 * "buy:ITEM:EVENT". Only debug builds pass anything other than "pantry" (used for screenshots).
 */
@Composable
fun KitchtallyRoot(startScreen: String = "pantry", demo: Boolean = false, vm: PantryViewModel = viewModel()) {
    var route by rememberSaveable { mutableStateOf(startScreen) }
    var tab by rememberSaveable { mutableStateOf(if (startScreen in listOf("shopping", "week")) startScreen else "pantry") }
    /** Where the purchase screen returns to. */
    var back by rememberSaveable { mutableStateOf("") }

    var today by remember { mutableStateOf(LocalDate.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) today = LocalDate.now() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(today) {
        val now = LocalDateTime.now()
        delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 1_000)
        today = LocalDate.now()
    }
    LaunchedEffect(Unit) { if (demo) vm.seedDemo() }

    val state by vm.state.collectAsStateWithLifecycle()
    val currency by vm.currency.collectAsStateWithLifecycle()
    val parts = route.split(":")
    val id1 = parts.getOrNull(1)?.toLongOrNull() ?: 0L
    val id2 = parts.getOrNull(2)?.toLongOrNull() ?: 0L

    BackHandler(enabled = route != tab) {
        route = when (parts[0]) {
            "edit" -> if (id1 != 0L) "item:$id1" else tab
            "buy" -> back.ifEmpty { "item:$id1" }
            else -> tab
        }
    }

    when (parts[0]) {
        "item" -> ItemScreen(
            vm = vm, state = state, id = id1, today = today, currency = currency,
            onBack = { route = tab },
            onEdit = { route = "edit:$id1" },
            onBuy = { eventId -> back = "item:$id1"; route = "buy:$id1:$eventId" },
        )
        "edit" -> EditItemScreen(
            vm = vm, state = state, id = id1,
            onDone = { savedId -> route = if (savedId != 0L) "item:$savedId" else tab },
        )
        "buy" -> PurchaseScreen(
            vm = vm, state = state, itemId = id1, eventId = id2, today = today, currency = currency,
            onDone = { route = back.ifEmpty { "item:$id1" } },
        )
        "settings" -> SettingsScreen(vm = vm, currency = currency, onBack = { route = tab })
        else -> MainTabs(
            vm = vm, state = state, tab = tab, today = today, currency = currency,
            onTab = { tab = it; route = it },
            onOpen = { route = "item:$it" },
            onAdd = { route = "edit:0" },
            onBuy = { back = tab; route = "buy:$it:0" },
            onSettings = { route = "settings" },
        )
    }
}

@Composable
private fun MainTabs(
    vm: PantryViewModel,
    state: PantryState?,
    tab: String,
    today: LocalDate,
    currency: String,
    onTab: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onBuy: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val toBuy = state?.items?.count { onShoppingList(it, state, today) } ?: 0
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            "shopping" -> "Shopping list"
                            "week" -> "This week"
                            else -> "Kitchtally"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == "pantry", onClick = { onTab("pantry") }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Pantry") })
                NavigationBarItem(
                    selected = tab == "shopping",
                    onClick = { onTab("shopping") },
                    icon = {
                        BadgedBox(badge = { if (toBuy + (state?.notes?.size ?: 0) > 0) Badge { Text("${toBuy + (state?.notes?.size ?: 0)}") } }) {
                            Icon(Icons.Default.ShoppingCart, null)
                        }
                    },
                    label = { Text("To buy") },
                )
                NavigationBarItem(selected = tab == "week", onClick = { onTab("week") }, icon = { Icon(Icons.Default.DateRange, null) }, label = { Text("Weekly") })
            }
        },
        floatingActionButton = {
            if (tab == "pantry") {
                FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add item") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state ?: return@Scaffold
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (tab) {
            "shopping" -> ShoppingList(vm, s, today, currency, snackbar, onOpen, onBuy, modifier)
            "week" -> WeekSummary(s, today, currency, onOpen, modifier)
            else -> PantryList(vm, s, today, currency, snackbar, onOpen, onAdd, onTab, modifier)
        }
    }
}

/* ============================ Pantry ============================ */

@Composable
private fun PantryList(
    vm: PantryViewModel,
    state: PantryState,
    today: LocalDate,
    currency: String,
    snackbar: SnackbarHostState,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onTab: (String) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var adjusting by rememberSaveable { mutableLongStateOf(0L) }
    state.item(adjusting)?.let { target ->
        AdjustDialog(target, state.stock(target.id), onDismiss = { adjusting = 0L }) { action, amount ->
            adjusting = 0L
            vm.adjust(state, target, action, amount) { message, undo -> scope.undoSnackbar(snackbar, "$message of ${target.name}", undo) }
        }
    }
    if (state.items.isEmpty()) {
        Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add the things you keep at home: rice, bread, atta, onions, shampoo. Then tap + when you buy more and − when you use some.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAdd) { Text("Add your first item") }
        }
        return
    }

    val categories = orderedCategories(state.usedCategories)
    val active = categories.firstOrNull { it == filter }
    val query = search.trim()
    val shown = state.items.filter {
        (active == null || it.category.equals(active, ignoreCase = true)) &&
            (query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.notes.contains(query, ignoreCase = true))
    }
    // Items you're not sure about get their own group at the bottom.
    val groups = categories.mapNotNull { c ->
        val list = shown.filter { !it.unsure && it.category.equals(c, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        if (list.isEmpty()) null else c to list
    } + listOfNotNull(shown.filter { it.unsure }.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let { UNSURE_GROUP to it })
    val week = Pantry.week(Pantry.weekStart(today), state.entriesByItem) { id -> state.item(id)?.category ?: "" }
    val toBuy = state.items.count { onShoppingList(it, state, today) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Column(Modifier.padding(16.dp)) {
                    Text("This week", style = MaterialTheme.typography.labelLarge, color = on)
                    Text(Money.format(week.spentMinor, currency), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = on)
                    Text("spent on groceries and more", style = MaterialTheme.typography.bodySmall, color = on)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(week.itemsUsed.toString(), "items used", Modifier.weight(1f)) { onTab("week") }
                        StatTile(week.timesShared.toString(), "times shared", Modifier.weight(1f)) { onTab("week") }
                        StatTile((toBuy + state.notes.size).toString(), "to buy", Modifier.weight(1f)) { onTab("shopping") }
                    }
                }
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(40) },
                placeholder = { Text("Search your pantry") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") } },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (groups.isEmpty()) {
            item(key = "no-results") {
                Text(
                    "Nothing matches \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        if (categories.size > 1) {
            item(key = "filter") {
                ChipRow(listOf<String?>(null) + categories, active, { it ?: "All" }, { filter = it ?: "" })
            }
        }
        groups.forEach { (category, items) ->
            item(key = "cat-$category") {
                Text(
                    if (category == UNSURE_GROUP) "$category · ${items.size}" else "${Categories.emojiFor(category)}  $category · ${items.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            items.forEach { row ->
                item(key = row.id) {
                    ItemRow(
                        item = row,
                        state = state,
                        today = today,
                        onClick = { onOpen(row.id) },
                        onUse = {
                            vm.use(row, shared = false) { eventId, amount ->
                                scope.undoSnackbar(snackbar, "Used ${row.unitValue().label(amount)} of ${row.name}") { vm.undo(eventId) }
                            }
                        },
                        onShare = {
                            vm.use(row, shared = true) { eventId, amount ->
                                scope.undoSnackbar(snackbar, "Shared ${row.unitValue().label(amount)} of ${row.name}") { vm.undo(eventId) }
                            }
                        },
                        onBuy = {
                            vm.quickBuy(state, row) { buy ->
                                scope.undoSnackbar(snackbar, "Added ${row.unitValue().label(buy.amount)} of ${row.name}") { vm.undoBuy(buy) }
                            }
                        },
                        onAdjust = { adjusting = row.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, state: PantryState, today: LocalDate, onClick: () -> Unit, onUse: () -> Unit, onShare: () -> Unit, onBuy: () -> Unit, onAdjust: () -> Unit) {
    val entries = state.entries(item.id)
    val stock = Pantry.stock(entries)
    val status = Pantry.status(entries, item.lowAt, today)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ItemImage(item.name, item.photoFile, size = 52.dp, category = item.category)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StockPill(stockLabel(item, stock) + (if (item.refillNeeded && status == StockStatus.OK) " · refill" else "") + "  ±", status, onClick = onAdjust)
                Text(detailLine(item, state, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedIconButton(onClick = onShare, enabled = stock > 0, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Person, contentDescription = "Shared 1 with friends", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = onUse, enabled = stock > 0, modifier = Modifier.size(40.dp)) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(onClick = onBuy, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Bought 1 more")
            }
        }
    }
}

/* ============================ Shopping list ============================ */

@Composable
private fun ShoppingList(
    vm: PantryViewModel,
    state: PantryState,
    today: LocalDate,
    currency: String,
    snackbar: SnackbarHostState,
    onOpen: (Long) -> Unit,
    onBuy: (Long) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var newText by rememberSaveable { mutableStateOf("") }
    val list = state.items.filter { onShoppingList(it, state, today) }
    fun status(item: Item) = Pantry.status(state.entries(item.id), item.lowAt, today)
    val sections = listOf(
        "Out" to list.filter { status(it) == StockStatus.OUT },
        "Running low" to list.filter { status(it) == StockStatus.LOW },
        "Refill needed" to list.filter { status(it) == StockStatus.OK },
    ).filter { it.second.isNotEmpty() }
    val estimate = list.sumOf { Pantry.expectedPriceMinor(it.unitValue(), state.entries(it.id), it.packSize) ?: 0L }
    val hidden = state.items.filter { it.hiddenFromList && Pantry.status(state.entries(it.id), it.lowAt, today) != StockStatus.OK }

    fun add() {
        val text = newText.trim()
        if (text.isEmpty()) return
        vm.addNote(text, state) { matched ->
            if (matched) scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("$text is in your pantry, so it's marked as needing a refill") }
        }
        newText = ""
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "add") {
            OutlinedTextField(
                value = newText,
                onValueChange = { newText = it.take(80) },
                placeholder = { Text("Add something to buy") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                trailingIcon = { IconButton(onClick = { add() }, enabled = newText.isNotBlank()) { Icon(Icons.Default.Add, contentDescription = "Add") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (list.isEmpty() && state.notes.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛒", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing to buy", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pantry items appear here when they run out, run low, or you mark them as needing a refill. You can also type anything above.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item(key = "summary") {
                val count = list.size + state.notes.size
                Text(
                    "$count to buy" + if (estimate > 0) " · about ${Money.format(estimate, currency)} at last prices" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (state.notes.isNotEmpty()) {
            item(key = "notes-title") { SectionTitle("📝  Also need") }
            state.notes.forEach { note ->
                item(key = "note-${note.id}") {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = false, onCheckedChange = {
                                vm.deleteNote(note)
                                scope.undoSnackbar(snackbar, "Got ${note.text}") { vm.restoreNote(note) }
                            })
                            Text(note.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        sections.forEach { (title, items) ->
            item(key = "section-$title") { SectionTitle(title) }
            items.sortedBy { it.name.lowercase() }.forEach { row ->
                item(key = "buy-${row.id}") {
                    val entries = state.entries(row.id)
                    val unit = row.unitValue()
                    val buyAmount = Pantry.buyAmount(unit, entries, row.packSize)
                    val expected = Pantry.expectedPriceMinor(unit, entries, row.packSize)
                    val shop = Pantry.lastPriced(entries)?.shop?.takeIf { it.isNotBlank() } ?: Pantry.lastBought(entries)?.shop ?: ""
                    Card(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ItemImage(row.name, row.photoFile, size = 46.dp, category = row.category)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    StockPill(stockLabel(row, Pantry.stock(entries)), Pantry.status(entries, row.lowAt, today))
                                    Text(
                                        "Usually ${unit.label(buyAmount)}" +
                                            (expected?.let { " · ${Money.format(it, currency)}" } ?: "") +
                                            (if (shop.isNotBlank()) " at $shop" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { menu = true }) { Text("Not now") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Hide until I buy it") },
                                            onClick = {
                                                menu = false
                                                if (row.refillNeeded && Pantry.status(entries, row.lowAt, today) == StockStatus.OK) {
                                                    vm.setRefillNeeded(row, false)
                                                } else {
                                                    vm.setHidden(row, true)
                                                    scope.undoSnackbar(snackbar, "${row.name} hidden until you buy it again") { vm.setHidden(row, false) }
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Not sure to buy again") },
                                            onClick = {
                                                menu = false
                                                vm.setUnsure(row, true)
                                                scope.undoSnackbar(snackbar, "${row.name} moved to \"Not sure to buy again\"") { vm.setUnsure(row, false) }
                                            },
                                        )
                                    }
                                }
                                TextButton(onClick = { onBuy(row.id) }) { Text("Price & shop") }
                                FilledTonalButton(onClick = {
                                    vm.quickBuy(state, row) { buy ->
                                        scope.undoSnackbar(snackbar, "Bought ${unit.label(buy.amount)} of ${row.name}") { vm.undoBuy(buy) }
                                    }
                                }) { Text("Bought") }
                            }
                        }
                    }
                }
            }
        }
        if (hidden.isNotEmpty()) {
            item(key = "hidden") {
                Text(
                    "Hidden until bought: " + hidden.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

/* ============================ Weekly summary ============================ */

@Composable
private fun WeekSummary(state: PantryState, today: LocalDate, currency: String, onOpen: (Long) -> Unit, modifier: Modifier) {
    var offset by rememberSaveable { mutableLongStateOf(0L) }
    val start = Pantry.weekStart(today).minusWeeks(offset)
    val categoryOf: (Long) -> String = { id -> state.item(id)?.category ?: "Other" }
    val week = Pantry.week(start, state.entriesByItem, categoryOf)
    val previous = Pantry.week(start.minusWeeks(1), state.entriesByItem, categoryOf)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "range") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset++ }) { Text("‹ Earlier") }
                Text(
                    "${start.format(shortDate)} – ${start.plusDays(6).format(shortDate)}",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { offset-- }, enabled = offset > 0) { Text("Later ›") }
            }
        }
        item(key = "totals") {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Column(Modifier.padding(16.dp)) {
                    Text("Spent", style = MaterialTheme.typography.labelLarge, color = on)
                    Text(Money.format(week.spentMinor, currency), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = on)
                    val diff = week.spentMinor - previous.spentMinor
                    if (previous.spentMinor > 0 || week.spentMinor > 0) {
                        Text(
                            when {
                                diff > 0 -> "${Money.format(diff, currency)} more than the week before"
                                diff < 0 -> "${Money.format(-diff, currency)} less than the week before"
                                else -> "Same as the week before"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = on,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(week.itemsUsed.toString(), "items used", Modifier.weight(1f))
                        StatTile(week.timesShared.toString(), "times shared", Modifier.weight(1f))
                        StatTile(week.itemsBought.toString(), "items bought", Modifier.weight(1f))
                    }
                }
            }
        }
        if (week.spentByCategory.isNotEmpty()) {
            item(key = "categories") {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Spent by category")
                        val max = week.spentByCategory.values.maxOrNull() ?: 1L
                        week.spentByCategory.entries.sortedByDescending { it.value }.forEach { (category, minor) ->
                            Column {
                                Row {
                                    Text("${Categories.emojiFor(category)}  $category", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(Money.format(minor, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(progress = { minor.toFloat() / max }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                            }
                        }
                    }
                }
            }
        }
        if (week.items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing used or bought this week.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            item(key = "items-title") { SectionLabel("Items") }
            week.items.forEach { w ->
                val thing = state.item(w.itemId) ?: return@forEach
                val unit = thing.unitValue()
                item(key = "w-${w.itemId}") {
                    Card(onClick = { onOpen(thing.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ItemImage(thing.name, thing.photoFile, size = 42.dp, category = thing.category)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(thing.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                val parts = buildList {
                                    if (w.used > 0) add("used ${unit.label(w.used)}" + if (w.shared > 0) " (${Qty.format(w.shared)} shared)" else "")
                                    if (w.bought > 0) add("bought ${unit.label(w.bought)}")
                                }
                                Text(parts.joinToString(" · ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (w.spentMinor > 0) Text(Money.format(w.spentMinor, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
