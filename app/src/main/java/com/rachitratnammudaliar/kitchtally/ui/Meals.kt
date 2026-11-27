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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🍲  Meals this week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (planned.isEmpty()) {
                Text("No meals planned. Add the dishes you'll cook and what each needs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            planned.sortedWith(compareBy({ if (it.day > 0) it.day else Long.MAX_VALUE }, { it.until })).forEach { meal ->
                val dish = dishes.firstOrNull { it.id == meal.dishId } ?: return@forEach
                val missing = dish.ingredients.filter { !ingredientOk(state, it, today) }.mapNotNull { state.item(it)?.name }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dish.name, fontWeight = FontWeight.Medium)
                        Text(
                            dayText(meal.day, today) + " · " + if (missing.isEmpty()) "✅ you have everything" else "Missing: " + missing.joinToString(", "),
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
                if (dishes.isEmpty()) Text("No saved dishes yet. Create your first one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(dishes.sortedBy { it.name.lowercase() }, key = { it.id }) { dish ->
                        Row(Modifier.fillMaxWidth().clickable { vm.planMeal(dish.id, day, today); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(dish.name, modifier = Modifier.weight(1f))
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

/** Name the dish and tick what it needs from your items, in stock or not. A missing item can be added on the spot. */
@Composable
fun DishEditorDialog(
    vm: PantryViewModel,
    state: PantryState,
    today: LocalDate,
    dishes: List<Dish>,
    dishId: Long,
    onDone: () -> Unit,
) {
    val existing = dishes.firstOrNull { it.id == dishId }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    val chosen = remember { mutableStateListOf<Long>().also { l -> existing?.ingredients?.let { l.addAll(it) } } }
    var query by remember { mutableStateOf("") }
    val list = state.items.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<Item> { it.id in chosen }.thenBy { it.name.lowercase() })
    val canAdd = query.isNotBlank() && state.items.none { it.name.equals(query.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "New dish" else "Edit dish") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Dish, e.g. Egg curry") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search or add an ingredient") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (canAdd) {
                    TextButton(onClick = {
                        val n = query.trim()
                        vm.saveItem(Item(name = n), 0.0, "") { id -> chosen.add(id) }
                        query = ""
                    }) { Text("＋ Add \"${query.trim()}\" as a new ingredient") }
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(list, key = { it.id }) { item ->
                        val ok = ingredientOk(state, item.id, today)
                        val label = when {
                            state.stock(item.id) <= 0 -> "❌ out"
                            !ok -> "⚠️ low · ${item.unitValue().label(state.stock(item.id))}"
                            else -> "✅ ${item.unitValue().label(state.stock(item.id))}"
                        }
                        val toggle = { if (item.id in chosen) chosen.remove(item.id) else chosen.add(item.id); Unit }
                        Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.id in chosen, onCheckedChange = { toggle() })
                            Text(item.name, modifier = Modifier.weight(1f))
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val id = vm.saveDish(dishId, name, chosen.toList())
                    if (existing == null) vm.planMeal(id, 0L, today)
                    onDone()
                },
            ) { Text(if (existing == null) "Save and plan" else "Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { vm.deleteDish(dishId); onDone() }) { Text("Delete dish") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}
