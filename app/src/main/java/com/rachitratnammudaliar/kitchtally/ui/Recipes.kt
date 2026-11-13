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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.Recipe
import com.rachitratnammudaliar.kitchtally.data.encodeLines
import com.rachitratnammudaliar.kitchtally.data.lineList
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Money
import com.rachitratnammudaliar.kitchtally.domain.Qty
import com.rachitratnammudaliar.kitchtally.domain.RecipeLine
import com.rachitratnammudaliar.kitchtally.domain.RecipeStatus
import com.rachitratnammudaliar.kitchtally.domain.Recipes
import com.rachitratnammudaliar.kitchtally.domain.StockStatus
import kotlinx.coroutines.launch
import java.time.LocalDate

/* ============================ Helpers shared with To buy ============================ */

/** An item that a planned meal still needs, and which meals want it. */
data class PlannedNeed(val item: Item, val recipes: List<Recipe>, val needed: Boolean)

/** What's missing for the meals you've planned. Needed things first, then optional ones. */
fun plannedNeeds(state: PantryState): List<PlannedNeed> {
    val wanted = linkedMapOf<Long, MutableList<Pair<Recipe, Boolean>>>()
    state.recipes.filter { it.plannedDay != Recipes.NOT_PLANNED }.forEach { recipe ->
        val lines = recipe.lineList()
        (Recipes.missing(lines, state::stock, true) + Recipes.missing(lines, state::stock, false)).forEach { line ->
            wanted.getOrPut(line.itemId) { mutableListOf() } += recipe to line.needed
        }
    }
    return wanted.mapNotNull { (id, list) ->
        val item = state.item(id) ?: return@mapNotNull null
        PlannedNeed(item, list.map { it.first }.distinctBy { it.id }, list.any { it.second })
    }.sortedWith(compareByDescending<PlannedNeed> { it.needed }.thenBy { it.item.name.lowercase() })
}

/** Everything on To buy: the usual list plus what planned meals need, counted once. */
fun toBuyCount(state: PantryState, today: LocalDate): Int =
    (state.items.filter { onShoppingList(it, state, today) }.map { it.id } + plannedNeeds(state).map { it.item.id }).distinct().size

/* ============================ Recipes tab ============================ */

@Composable
fun RecipesScreen(
    vm: PantryViewModel,
    state: PantryState,
    today: LocalDate,
    currency: String,
    snackbar: SnackbarHostState,
    onEdit: (Long) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var onlyReady by rememberSaveable { mutableStateOf(false) }
    var cooking by rememberSaveable { mutableStateOf(0L) }
    val rows = state.recipes.map { it to Recipes.status(it.lineList(), state::stock) }
    val shown = rows
        .filter { !onlyReady || it.second == RecipeStatus.READY || it.second == RecipeStatus.MISSING_OPTIONAL }
        .sortedWith(compareBy({ it.second.ordinal }, { it.first.name.lowercase() }))
    val planned = state.recipes.count { it.plannedDay != Recipes.NOT_PLANNED }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.recipes.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🍲", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("No recipes yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap ＋ to add a dish, like Chole. Pick what goes in from your pantry. Then cooking it takes the right amounts off your stock.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }
        item(key = "filter") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = onlyReady, onClick = { onlyReady = !onlyReady }, label = { Text("What can I cook?") })
                if (planned > 0) Text("$planned planned", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (shown.isEmpty()) {
            item(key = "none") { Hint("Nothing is fully in stock right now. Turn the filter off to see everything.") }
        }
        shown.forEach { (recipe, status) ->
            item(key = "recipe-${recipe.id}") {
                val lines = recipe.lineList()
                val missingNeeded = Recipes.missing(lines, state::stock, true)
                val cost = Recipes.cost(lines) { id -> Recipes.unitPriceMinor(state.entries(id)) }
                Card(onClick = { cooking = recipe.id }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(recipe.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StockPill(
                                when (status) {
                                    RecipeStatus.READY -> "Can cook now"
                                    RecipeStatus.MISSING_OPTIONAL -> "Can cook · missing " + Recipes.missing(lines, state::stock, false).mapNotNull { state.item(it.itemId)?.name }.joinToString()
                                    RecipeStatus.MISSING -> "Missing " + missingNeeded.mapNotNull { state.item(it.itemId)?.name }.joinToString()
                                },
                                when (status) {
                                    RecipeStatus.READY -> StockStatus.OK
                                    RecipeStatus.MISSING_OPTIONAL -> StockStatus.LOW
                                    RecipeStatus.MISSING -> StockStatus.OUT
                                },
                            )
                            Text(
                                listOfNotNull(
                                    "${recipe.mealsAlone} meals, or ${recipe.mealsShared} shared",
                                    cost?.let { "about ${Money.format(it / recipe.mealsAlone.coerceAtLeast(1), currency)} a meal" },
                                    if (recipe.plannedDay != Recipes.NOT_PLANNED) "🍳 ${Recipes.planLabel(recipe.plannedDay, today)}" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEdit(recipe.id) }) { Icon(Icons.Default.Edit, contentDescription = "Edit ${recipe.name}") }
                    }
                }
            }
        }
    }

    state.recipes.firstOrNull { it.id == cooking }?.let { recipe ->
        CookRecipeDialog(
            recipe = recipe,
            state = state,
            onDismiss = { cooking = 0L },
            onAddMissing = { items ->
                vm.addMissingToBuy(items)
                cooking = 0L
                scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Added ${items.joinToString { it.name }} to To buy") }
            },
            onCook = { amounts, shared ->
                cooking = 0L
                vm.cookRecipe(recipe, amounts, shared) { message, undo -> scope.undoSnackbar(snackbar, message, undo) }
            },
        )
    }
}

/**
 * Cook a recipe: shows each ingredient with its usual amount, which you can change with 25 / 50 / 75 / All
 * of what's left, or by typing. Then the stock goes down by those amounts.
 */
@Composable
private fun CookRecipeDialog(
    recipe: Recipe,
    state: PantryState,
    onDismiss: () -> Unit,
    onAddMissing: (List<Item>) -> Unit,
    onCook: (List<Pair<Item, Double>>, Boolean) -> Unit,
) {
    val lines = remember(recipe) { recipe.lineList().filter { state.item(it.itemId) != null } }
    var shared by rememberSaveable { mutableStateOf(false) }
    val included = remember(recipe) { mutableStateMapOf<Long, Boolean>().also { m -> lines.forEach { m[it.itemId] = state.stock(it.itemId) > 0 && (it.needed || Recipes.has(it, state.stock(it.itemId))) } } }
    val texts = remember(recipe) {
        mutableStateMapOf<Long, String>().also { m ->
            lines.forEach { line ->
                val item = state.item(line.itemId)!!
                val stock = state.stock(line.itemId)
                val start = if (line.amount > 0) minOf(line.amount, stock) else minOf(item.step, stock)
                m[line.itemId] = Qty.format(Qty.round(start))
            }
        }
    }
    val missingNeeded = Recipes.missing(lines, state::stock, true).mapNotNull { state.item(it.itemId) }
    fun amountOf(line: RecipeLine): Double? = texts[line.itemId]?.let { Qty.parse(it) }
    val picked = lines.filter { included[it.itemId] == true }
    val invalid = picked.any { (amountOf(it) ?: 0.0) <= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🍳  ${recipe.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(listOf(false, true), shared, { if (it) "With friends · ${recipe.mealsShared} meals" else "Just me · ${recipe.mealsAlone} meals" }, { shared = it })
                if (lines.isEmpty()) {
                    Hint("This recipe has no ingredients yet. Tap the pencil on the card to add some.")
                    return@Column
                }
                if (missingNeeded.isNotEmpty()) Hint("Missing: ${missingNeeded.joinToString { it.name }}", error = true)
                Hint("Change how much goes in. The stock goes down by these amounts.")
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { line ->
                        item(key = line.itemId) {
                            val item = state.item(line.itemId)!!
                            val stock = state.stock(line.itemId)
                            val unit = item.unitValue()
                            val empty = stock <= 0
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = included[line.itemId] == true,
                                        onCheckedChange = { included[line.itemId] = it },
                                        enabled = !empty,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name + if (line.needed) "" else " (optional)", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (empty) "None left" else "${unit.label(stock)} left",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (empty && line.needed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    OutlinedTextField(
                                        value = texts[line.itemId].orEmpty(),
                                        onValueChange = { v -> texts[line.itemId] = v.filter { it.isDigit() || it == '.' || it == ',' }.take(8); included[line.itemId] = true },
                                        singleLine = true,
                                        enabled = !empty,
                                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                        modifier = Modifier.width(88.dp),
                                    )
                                }
                                if (!empty) {
                                    Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(0.25 to "25%", 0.5 to "50%", 0.75 to "75%", 1.0 to "All").forEach { (part, label) ->
                                            FilterChip(
                                                selected = false,
                                                onClick = { texts[line.itemId] = Qty.format(Qty.round(stock * part)); included[line.itemId] = true },
                                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCook(picked.mapNotNull { l -> state.item(l.itemId)?.let { it to (amountOf(l) ?: 0.0) } }, shared) },
                enabled = picked.isNotEmpty() && !invalid,
            ) { Text("Cooked") }
        },
        dismissButton = {
            Row {
                if (missingNeeded.isNotEmpty()) TextButton(onClick = { onAddMissing(missingNeeded) }) { Text("Add missing to buy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/* ============================ Recipe editor ============================ */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditRecipeScreen(vm: PantryViewModel, state: PantryState?, id: Long, today: LocalDate, onDone: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val existing = state?.recipes?.firstOrNull { it.id == id }
    val isNew = id == 0L
    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var name by rememberSaveable { mutableStateOf("") }
    var alone by rememberSaveable { mutableStateOf("4") }
    var shared by rememberSaveable { mutableStateOf("2") }
    var plan by rememberSaveable { mutableStateOf(Recipes.NOT_PLANNED) }
    val lines = remember { mutableStateListOf<RecipeLine>() }
    val amountTexts = remember { mutableStateMapOf<Long, String>() }
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(existing != null) {
        if (loaded || existing == null) return@LaunchedEffect
        name = existing.name
        alone = existing.mealsAlone.toString()
        shared = existing.mealsShared.toString()
        plan = existing.plannedDay
        lines.clear()
        lines.addAll(existing.lineList())
        existing.lineList().forEach { amountTexts[it.itemId] = if (it.amount > 0) Qty.format(it.amount) else "" }
        loaded = true
    }
    BackHandler { onDone() }

    val aloneValue = alone.toIntOrNull()?.takeIf { it in 1..99 }
    val sharedValue = shared.toIntOrNull()?.takeIf { it in 1..99 }
    val nameError = name.isBlank()
    val amountsOk = lines.all { amountTexts[it.itemId].orEmpty().let { t -> t.isBlank() || Qty.parse(t) != null } }

    fun save() {
        if (nameError || aloneValue == null || sharedValue == null || !amountsOk) { showErrors = true; return }
        val saved = lines.map { it.copy(amount = amountTexts[it.itemId].orEmpty().let { t -> if (t.isBlank()) 0.0 else Qty.parse(t) ?: 0.0 }) }
        vm.saveRecipe(
            Recipe(id = id, name = name.trim().take(60), mealsAlone = aloneValue, mealsShared = sharedValue, lines = encodeLines(saved), plannedDay = plan),
            onSaved = onDone,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New recipe" else "Edit recipe") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { save() }) { Text("Save") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!loaded || state == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Recipe name, e.g. Chole") },
                singleLine = true,
                isError = showErrors && nameError,
                supportingText = errorText(showErrors && nameError, "Give it a name"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("One cook makes", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = alone, onValueChange = { alone = it.filter(Char::isDigit).take(2) }, label = { Text("Meals for me") }, singleLine = true,
                    isError = showErrors && aloneValue == null, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                OutlinedTextField(
                    value = shared, onValueChange = { shared = it.filter(Char::isDigit).take(2) }, label = { Text("Meals if shared") }, singleLine = true,
                    isError = showErrors && sharedValue == null, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
            Hint("Either you eat it yourself and get $alone meals, or you share it and it makes $shared.")

            Text("Ingredients", style = MaterialTheme.typography.titleSmall)
            if (lines.isEmpty()) Hint("Add what goes in, from your pantry. Mark things like garlic or lemon as optional.")
            lines.toList().forEach { line ->
                val item = state.item(line.itemId)
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item?.name ?: "Removed item", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { lines.removeAll { it.itemId == line.itemId } }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                        }
                        ChipRow(listOf(true, false), line.needed, { if (it) "Needed" else "Optional" }, { n ->
                            val i = lines.indexOfFirst { it.itemId == line.itemId }
                            if (i >= 0) lines[i] = lines[i].copy(needed = n)
                        })
                        if (item != null) {
                            OutlinedTextField(
                                value = amountTexts[line.itemId].orEmpty(),
                                onValueChange = { amountTexts[line.itemId] = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) },
                                label = { Text("Usual amount (${item.unitValue().plural})") },
                                placeholder = { Text("a little") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("＋  Add ingredient") }
            if (state.items.isEmpty()) Hint("Add items to your pantry first, then pick them here.")

            Text("Plan it", style = MaterialTheme.typography.titleSmall)
            val days = (0..6).map { today.plusDays(it.toLong()).toEpochDay() }
            ChipRow(
                listOf(Recipes.NOT_PLANNED, Recipes.ANY_DAY) + days,
                plan,
                { Recipes.planLabel(it, today) },
                { plan = it },
            )
            Hint("Planned meals put what's missing on To buy, with the reason. Not planned means nothing is added.")

            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete recipe", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking && state != null) {
        var search by rememberSaveable { mutableStateOf("") }
        val available = state.items.filter { i -> lines.none { it.itemId == i.id } && (search.isBlank() || i.name.contains(search.trim(), ignoreCase = true)) }
            .sortedBy { it.name.lowercase() }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Add ingredient") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it.take(40) }, placeholder = { Text("Search") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true,
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        available.forEach { item ->
                            item(key = item.id) {
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        lines += RecipeLine(item.id, true, 0.0)
                                        picking = false
                                    }.padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ItemImage(item.name, item.photoFile, size = 36.dp, category = item.category)
                                    Spacer(Modifier.width(10.dp))
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (available.isEmpty()) item(key = "none") { Hint("No items found. Add it to your pantry first.") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Your pantry stays as it is. Only the recipe goes.") },
            confirmButton = { TextButton(onClick = { vm.deleteRecipe(existing); confirmDelete = false; onDone() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
