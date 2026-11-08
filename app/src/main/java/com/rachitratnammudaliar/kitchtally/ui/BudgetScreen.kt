package com.rachitratnammudaliar.kitchtally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Categories
import com.rachitratnammudaliar.kitchtally.domain.Money
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Money, month by month: everyday shopping and long-lasting things (face cream, toilet paper) apart. */
@Composable
fun BudgetScreen(vm: PantryViewModel, state: PantryState, today: LocalDate, currency: String, onOpen: (Long) -> Unit, modifier: Modifier) {
    var offset by rememberSaveable { mutableLongStateOf(0L) }
    val budget by vm.budgetMinor.collectAsStateWithLifecycle()
    val first = today.withDayOfMonth(1).minusMonths(offset)
    val categoryOf: (Long) -> String = { id -> state.item(id)?.category ?: "Other" }
    val month = Pantry.month(first, state.entriesByItem, isLong = { state.isLong(it) }, categoryOf = categoryOf)
    val before = Pantry.month(first.minusMonths(1), state.entriesByItem, isLong = { state.isLong(it) }, categoryOf = categoryOf)
    val label = first.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    val dayFormat = DateTimeFormatter.ofPattern("d MMM")

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "range") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { offset++ }) { Text("‹ Earlier") }
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = { offset-- }, enabled = offset > 0) { Text("Later ›") }
            }
        }
        item(key = "everyday") {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everyday shopping", style = MaterialTheme.typography.labelLarge, color = on)
                    Text(Money.format(month.everydayMinor, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = on)
                    if (budget > 0) {
                        Text("of ${Money.format(budget, currency)} budget", style = MaterialTheme.typography.bodyMedium, color = on)
                        LinearProgressIndicator(progress = { (month.everydayMinor.toFloat() / budget).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                        Text(
                            if (month.everydayMinor <= budget) "${Money.format(budget - month.everydayMinor, currency)} left" else "${Money.format(month.everydayMinor - budget, currency)} over",
                            style = MaterialTheme.typography.bodySmall,
                            color = on,
                        )
                    }
                    if (before.everydayMinor > 0 || month.everydayMinor > 0) {
                        val diff = month.everydayMinor - before.everydayMinor
                        Text(
                            when {
                                diff > 0 -> "${Money.format(diff, currency)} more than the month before"
                                diff < 0 -> "${Money.format(-diff, currency)} less than the month before"
                                else -> "Same as the month before"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = on,
                        )
                    }
                }
            }
        }
        item(key = "long") {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Long-lasting things", style = MaterialTheme.typography.labelLarge)
                    Text(Money.format(month.longMinor, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Hint("Face cream, toilet paper, shower gel and other non-food things that last a month or more. They are kept out of the weekly shop.")
                    if (before.longMinor > 0 || month.longMinor > 0) {
                        val diff = month.longMinor - before.longMinor
                        Hint(
                            when {
                                diff > 0 -> "${Money.format(diff, currency)} more than the month before"
                                diff < 0 -> "${Money.format(-diff, currency)} less than the month before"
                                else -> "Same as the month before"
                            },
                        )
                    }
                }
            }
        }
        categoryBars("everyday", "Everyday, by category", month.everydayByCategory, currency)
        categoryBars("long-cats", "Long-lasting, by category", month.longByCategory, currency)
        if (month.longBuys.isNotEmpty()) {
            item(key = "long-buys-title") { SectionLabel("Long-lasting purchases") }
            month.longBuys.forEachIndexed { i, buy ->
                val thing = state.item(buy.itemId) ?: return@forEachIndexed
                item(key = "lb-$i-${buy.itemId}-${buy.date.toEpochDay()}") {
                    WeekRow(thing, "Bought ${thing.unitValue().label(buy.quantity)} · ${buy.date.format(dayFormat)}", if (buy.spentMinor > 0) Money.format(buy.spentMinor, currency) else null) { onOpen(thing.id) }
                }
            }
        }
        if (month.everydayMinor == 0L && month.longMinor == 0L) {
            item(key = "empty") {
                Text(
                    "No prices logged in $label. Add a price when you buy something and it shows up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.categoryBars(key: String, title: String, byCategory: Map<String, Long>, currency: String) {
    if (byCategory.isEmpty()) return
    item(key = key) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(title)
                val max = byCategory.values.maxOrNull() ?: 1L
                byCategory.entries.sortedByDescending { it.value }.forEach { (category, minor) ->
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
