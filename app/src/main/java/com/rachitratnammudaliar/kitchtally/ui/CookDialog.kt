package com.rachitratnammudaliar.kitchtally.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Meals

/**
 * Cook a meal: tick what goes in, then one tap takes one meal from each (or one tap's worth for
 * things like oil and salt). Items that count meals are listed first.
 */
@Composable
fun CookDialog(state: PantryState, onDismiss: () -> Unit, onCook: (items: List<Item>, shared: Boolean) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var search by rememberSaveable { mutableStateOf("") }
    val available = state.items
        .filter { state.stock(it.id) > 0 }
        .sortedWith(compareByDescending<Item> { it.countMeals && it.mealsPerUnit > 0 }.thenBy { it.name.lowercase() })
    val shown = available.filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) || it.id in chosen }
    val picked = available.filter { it.id in chosen }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🍳  Cook a meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (available.isEmpty()) {
                    Text("Nothing in the pantry yet. Tap ＋ on what you have first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                Hint("Tick what you're cooking with. Each one goes down by one meal, or by one tap for things like oil.")
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(40) },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    shown.forEach { item ->
                        item(key = item.id) {
                            val stock = state.stock(item.id)
                            val unit = item.unitValue()
                            val amount = Meals.cookAmount(item.step, if (item.countMeals) item.mealsPerUnit else 0.0)
                            Row(
                                Modifier.fillMaxWidth().clickable { chosen = if (item.id in chosen) chosen - item.id else chosen + item.id }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = item.id in chosen, onCheckedChange = { chosen = if (item.id in chosen) chosen - item.id else chosen + item.id })
                                ItemImage(item.name, item.photoFile, size = 36.dp, category = item.category)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(stockLabel(item, stock), mealsText(item, stock), "uses ${unit.label(minOf(amount, stock))}").joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCook(picked, true) }, enabled = picked.isNotEmpty()) { Text("With friends") }
                Button(onClick = { onCook(picked, false) }, enabled = picked.isNotEmpty()) { Text("Cooked") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
