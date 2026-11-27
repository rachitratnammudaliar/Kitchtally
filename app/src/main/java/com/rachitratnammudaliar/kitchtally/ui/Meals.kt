package com.rachitratnammudaliar.kitchtally.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.counting
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Dish
import com.rachitratnammudaliar.kitchtally.domain.MealPlan
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.PlannedMeal
import com.rachitratnammudaliar.kitchtally.domain.StockStatus
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** True when you have enough of the item: in stock and not running low. Items that no longer exist don't block anything. */
fun ingredientOk(state: PantryState, id: Long, today: LocalDate): Boolean {
    val item = state.item(id) ?: return true
    return state.stock(id) > 0 && Pantry.status(state.entries(id), item.counting(), today) == StockStatus.OK
}

private fun dayText(day: Long, today: LocalDate): String = when {
    day <= 0L -> "This week"
    day == today.toEpochDay() -> "Today"
    day == today.toEpochDay() + 1 -> "Tomorrow"
    else -> LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
}

@Composable
fun MealsCard(
    vm: PantryViewModel,
    state: PantryState,
    today: LocalDate,
    planned: List<PlannedMeal>,
    dishes: List<Dish>,
    onAdd: () -> Unit,
) {
    val showMeals by vm.showMealCount.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🍲  Meals this week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (showMeals && planned.isNotEmpty()) {
                val total = MealPlan.mealsCovered(planned, dishes)
                Text("🍽  $total ${if (total == 1) "meal" else "meals"} from ${planned.size} ${if (planned.size == 1) "cooking" else "cookings"}", style = MaterialTheme.typography.bodyMedium)
            }
            if (planned.isEmpty()) {
                Text("No meals planned. Add the dishes you'll cook and what each needs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            planned.sortedWith(compareBy({ if (it.day > 0) it.day else Long.MAX_VALUE }, { it.until })).forEach { meal ->
                val dish = dishes.firstOrNull { it.id == meal.dishId } ?: return@forEach
                val missing = dish.essentials.filter { !ingredientOk(state, it, today) }.mapNotNull { state.item(it)?.name }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ItemImage(dish.name, dish.photo, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(dish.name, fontWeight = FontWeight.Medium)
                        Text(
                            dayText(meal.day, today) + (if (showMeals && dish.meals > 1) " · ${dish.meals} meals" else "") + " · " + if (missing.isEmpty()) "✅ you have everything" else "Missing: " + missing.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.unplanMeal(meal) }) { Text("Remove") }
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("＋ Meal") }
        }
    }
}

/** Pick a saved dish to plan, choose its day, or start a new dish. */
@Composable
fun DishPickerDialog(
    vm: PantryViewModel,
    today: LocalDate,
    dishes: List<Dish>,
    onEdit: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var day by remember { mutableStateOf(0L) }
    val plannedAll by vm.plannedMeals.collectAsState()
    val lastWeek = MealPlan.lastWeek(plannedAll, today.toEpochDay()).mapNotNull { id -> dishes.firstOrNull { it.id == id } }
    val thisWeek = MealPlan.upcoming(plannedAll, today.toEpochDay()).map { it.dishId }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = day == 0L, onClick = { day = 0L }, label = { Text("Any day") })
                    (0L..6L).forEach { o ->
                        val d = today.plusDays(o)
                        FilterChip(selected = day == d.toEpochDay(), onClick = { day = d.toEpochDay() }, label = { Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                    }
                }
                val fresh = lastWeek.filter { it.id !in thisWeek }
                if (fresh.isNotEmpty()) {
                    Button(onClick = { fresh.forEach { vm.planMeal(it.id, 0L, today) }; onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Same as last week (${fresh.size} ${if (fresh.size == 1) "meal" else "meals"})")
                    }
                    Text("Last week: " + fresh.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (dishes.isEmpty()) Text("No saved dishes yet. Create your first one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(dishes.sortedBy { it.name.lowercase() }, key = { it.id }) { dish ->
                        Row(Modifier.fillMaxWidth().clickable { vm.planMeal(dish.id, day, today); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ItemImage(dish.name, dish.photo, size = 36.dp)
                            Text(dish.name, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            TextButton(onClick = { onEdit(dish.id) }) { Text("Edit") }
                        }
                    }
                }
                Button(onClick = { onEdit(0L) }, modifier = Modifier.fillMaxWidth()) { Text("＋ New dish") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Full-screen dish page: photo, name, and the ingredients grouped by what you still need to buy.
 * Each ingredient is either needed or optional; only needed ones that are out or low go on the shopping list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DishScreen(
    vm: PantryViewModel,
    state: PantryState,
    today: LocalDate,
    dishes: List<Dish>,
    dishId: Long,
    onDone: () -> Unit,
) {
    val existing = dishes.firstOrNull { it.id == dishId }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var photo by rememberSaveable { mutableStateOf(existing?.photo ?: "") }
    val originalPhoto = existing?.photo ?: ""
    val chosen = remember { mutableStateListOf<Long>().also { l -> existing?.ingredients?.let { l.addAll(it) } } }
    val optional = remember { mutableStateListOf<Long>().also { l -> existing?.optional?.let { l.addAll(it) } } }
    var query by rememberSaveable { mutableStateOf("") }
    var meals by rememberSaveable { mutableStateOf(existing?.meals ?: 1) }
    val showMeals by vm.showMealCount.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    fun leave() {
        if (photo.isNotBlank() && photo != originalPhoto) vm.discardPhoto(photo)
        onDone()
    }
    BackHandler(onBack = ::leave)

    val chosenItems = chosen.mapNotNull { state.item(it) }
    val toBuy = chosenItems.filter { it.id !in optional && !ingredientOk(state, it.id, today) }.sortedBy { it.name.lowercase() }
    val have = chosenItems.filter { it.id !in optional && ingredientOk(state, it.id, today) }.sortedBy { it.name.lowercase() }
    val optionalItems = chosenItems.filter { it.id in optional }.sortedBy { it.name.lowercase() }
    val q = query.trim()
    val addable = state.items.filter { it.id !in chosen && (q.isBlank() || it.name.contains(q, ignoreCase = true)) }.sortedBy { it.name.lowercase() }
    val canCreate = q.isNotBlank() && state.items.none { it.name.equals(q, ignoreCase = true) }

    @Composable
    fun row(item: Item) {
        val ok = ingredientOk(state, item.id, today)
        val isOptional = item.id in optional
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name)
                Text(
                    when {
                        state.stock(item.id) <= 0 -> "Out of stock"
                        !ok -> "Running low · ${item.unitValue().label(state.stock(item.id))}"
                        else -> "Have ${item.unitValue().label(state.stock(item.id))}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = !isOptional,
                onClick = { if (isOptional) optional.remove(item.id) else optional.add(item.id) },
                label = { Text(if (isOptional) "Optional" else "Needed") },
            )
            TextButton(onClick = { chosen.remove(item.id); optional.remove(item.id) }) { Text("✕") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New dish" else "Edit dish", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (existing != null) TextButton(onClick = { vm.deleteDish(dishId); onDone() }) { Text("Delete") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        if (originalPhoto.isNotBlank() && photo != originalPhoto) vm.discardPhoto(originalPhoto)
                        val id = vm.saveDish(dishId, name, chosen.toList(), optional.toSet(), photo, meals)
                        if (existing == null) vm.planMeal(id, 0L, today)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(
                        (if (existing == null) "Save and plan" else "Save") +
                            if (toBuy.isNotEmpty()) " · ${toBuy.size} to buy" else "",
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ItemImage(name.ifBlank { "Dish" }, photo, size = 72.dp)
                        PhotoButtons(
                            vm = vm,
                            hasPhoto = photo.isNotBlank(),
                            snackbar = snackbar,
                            onPicked = { newFile ->
                                if (photo.isNotBlank() && photo != originalPhoto) vm.discardPhoto(photo)
                                photo = newFile
                            },
                            onRemove = {
                                if (photo.isNotBlank() && photo != originalPhoto) vm.discardPhoto(photo)
                                photo = ""
                            },
                        )
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Dish, e.g. Egg curry") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (showMeals) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Cook once, eat $meals ${if (meals == 1) "time" else "times"}", fontWeight = FontWeight.Medium)
                                Text("How many meals one cooking gives", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { meals = (meals - 1).coerceAtLeast(1) }, enabled = meals > 1) { Text("−") }
                            Text("$meals", modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.SemiBold)
                            OutlinedButton(onClick = { meals = (meals + 1).coerceAtMost(20) }, enabled = meals < 20) { Text("＋") }
                        }
                    }
                }
            }
            if (toBuy.isNotEmpty()) {
                item(key = "buyh") { Text("🛒  To buy · goes on your shopping list", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(toBuy, key = { "b${it.id}" }) { row(it) }
            }
            if (have.isNotEmpty()) {
                item(key = "haveh") { Text("✅  You have", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(have, key = { "h${it.id}" }) { row(it) }
            }
            if (optionalItems.isNotEmpty()) {
                item(key = "opth") { Text("➕  Optional · never added to the list", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(optionalItems, key = { "o${it.id}" }) { row(it) }
            }
            item(key = "addh") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Add ingredients", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search, or type a new one") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (canCreate) {
                        Button(onClick = {
                            vm.saveItem(Item(name = q), 0.0, "") { id -> chosen.add(id) }
                            query = ""
                        }, modifier = Modifier.fillMaxWidth()) { Text("＋ Add \"$q\" as a new ingredient") }
                    }
                }
            }
            items(addable, key = { "a${it.id}" }) { item ->
                val ok = ingredientOk(state, item.id, today)
                Row(Modifier.fillMaxWidth().clickable { chosen.add(item.id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, modifier = Modifier.weight(1f))
                    Text(if (state.stock(item.id) <= 0) "❌ out" else if (!ok) "⚠️ low" else "✅ have", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { chosen.add(item.id) }) { Text("＋ Add") }
                }
            }
        }
    }
}
