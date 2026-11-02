@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.kitchtally.ui

import androidx.activity.compose.BackHandler
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

fun onShoppingList(item: Item, state: PantryState, today: LocalDate): Boolean =
    item.refillNeeded || Pantry.status(state.entries(item.id), item.lowAt, today) != StockStatus.OK

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
            "buy" -> "item:$id1"
            else -> tab
        }
    }

    when (parts[0]) {
        "item" -> ItemScreen(
            vm = vm, state = state, id = id1, today = today, currency = currency,
            onBack = { route = tab },
            onEdit = { route = "edit:$id1" },
            onBuy = { eventId -> route = "buy:$id1:$eventId" },
        )
        "edit" -> EditItemScreen(
            vm = vm, state = state, id = id1,
            onDone = { savedId -> route = if (savedId != 0L) "item:$savedId" else tab },
        )
        "buy" -> PurchaseScreen(
            vm = vm, state = state, itemId = id1, eventId = id2, today = today, currency = currency,
            onDone = { route = "item:$id1" },
        )
        "settings" -> SettingsScreen(vm = vm, currency = currency, onBack = { route = tab })
        else -> MainTabs(
            vm = vm, state = state, tab = tab, today = today, currency = currency,
            onTab = { tab = it; route = it },
            onOpen = { route = "item:$it" },
            onAdd = { route = "edit:0" },
            onBuy = { route = "buy:$it:0" },
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
                    icon = { Icon(Icons.Default.ShoppingCart, null) },
                    label = { Text(if (toBuy > 0) "To buy ($toBuy)" else "To buy") },
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
    val shown = state.items.filter { active == null || it.category.equals(active, ignoreCase = true) }
    val groups = categories.mapNotNull { c ->
        val list = shown.filter { it.category.equals(c, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        if (list.isEmpty()) null else c to list
    }
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
                        StatTile(Qty.format(week.usedTotal), "used", Modifier.weight(1f)) { onTab("week") }
                        StatTile(Qty.format(week.sharedTotal), "shared", Modifier.weight(1f)) { onTab("week") }
                        StatTile(toBuy.toString(), "to buy", Modifier.weight(1f)) { onTab("shopping") }
                    }
                }
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
                    "${Categories.emojiFor(category)}  $category · ${items.size}",
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
                            vm.log(row.id, EventType.USED) { eventId ->
                                scope.undoSnackbar(snackbar, "Used 1 ${row.unitValue().singular} of ${row.name}") { vm.undo(eventId) }
                            }
                        },
                        onShare = {
                            vm.log(row.id, EventType.SHARED) { eventId ->
                                scope.undoSnackbar(snackbar, "Shared 1 ${row.unitValue().singular} of ${row.name}") { vm.undo(eventId) }
                            }
                        },
                        onBuy = {
                            vm.quickBuy(state, row.id) { eventId ->
                                scope.undoSnackbar(snackbar, "Added 1 ${row.unitValue().singular} of ${row.name}") { vm.undo(eventId) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, state: PantryState, today: LocalDate, onClick: () -> Unit, onUse: () -> Unit, onShare: () -> Unit, onBuy: () -> Unit) {
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
                StockPill(stockLabel(item, stock) + if (item.refillNeeded && status == StockStatus.OK) " · refill" else "", status)
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
    val list = state.items.filter { onShoppingList(it, state, today) }
    if (list.isEmpty()) {
        Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing to buy", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Items appear here when they run out, run low, or you mark them as needing a refill.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val sections = listOf(
        "Out" to list.filter { Pantry.status(state.entries(it.id), it.lowAt, today) == StockStatus.OUT },
        "Running low" to list.filter { Pantry.status(state.entries(it.id), it.lowAt, today) == StockStatus.LOW },
        "Refill needed" to list.filter { Pantry.status(state.entries(it.id), it.lowAt, today) == StockStatus.OK },
    ).filter { it.second.isNotEmpty() }
    val estimate = list.sumOf { item ->
        Pantry.lastPriced(state.entries(item.id))?.let { Pantry.unitPriceMinor(it) } ?: 0L
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Text(
                "${list.size} item${if (list.size == 1) "" else "s"}" + if (estimate > 0) " · about ${Money.format(estimate, currency)} at last prices" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        sections.forEach { (title, items) ->
            item(key = "section-$title") {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            items.sortedBy { it.name.lowercase() }.forEach { row ->
                item(key = "buy-${row.id}") {
                    val entries = state.entries(row.id)
                    val last = Pantry.lastPriced(entries)
                    val unitPrice = last?.let { Pantry.unitPriceMinor(it) }
                    Card(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ItemImage(row.name, row.photoFile, size = 46.dp, category = row.category)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stockLabel(row, Pantry.stock(entries)) +
                                        (unitPrice?.let { " · ${Money.format(it, currency)} ${row.unitValue().priceLabel}" } ?: "") +
                                        (last?.shop?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onBuy(row.id) }) { Text("Details") }
                            FilledTonalButton(onClick = {
                                vm.quickBuy(state, row.id) { eventId ->
                                    scope.undoSnackbar(snackbar, "Bought 1 ${row.unitValue().singular} of ${row.name}") { vm.undo(eventId) }
                                }
                            }) { Text("Bought") }
                        }
                    }
                }
            }
        }
    }
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
                        StatTile(Qty.format(week.usedTotal), "used", Modifier.weight(1f))
                        StatTile(Qty.format(week.sharedTotal), "shared", Modifier.weight(1f))
                        StatTile(week.items.count { it.bought > 0 }.toString(), "items bought", Modifier.weight(1f))
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
