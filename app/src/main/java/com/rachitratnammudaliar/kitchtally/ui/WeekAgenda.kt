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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Money
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.Recipes
import com.rachitratnammudaliar.kitchtally.domain.WeekPlan
import com.rachitratnammudaliar.kitchtally.domain.WeeklyBudget
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Plan the next 7 days from your pantry. Whatever you won't have enough of goes on the shopping list in one tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekAgendaScreen(vm: PantryViewModel, state: PantryState?, today: LocalDate, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val planAll by vm.weekPlan.collectAsState()
    val plan = WeekPlan.upcoming(planAll, today.toEpochDay())
    var picking by rememberSaveable { mutableStateOf<Long?>(null) }
    val days = (0L..6L).map { today.plusDays(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Week agenda", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (plan.isNotEmpty()) TextButton(onClick = { vm.planClear() }) { Text("Clear") } },
            )
        },
    ) { padding ->
        if (state == null) return@Scaffold
        val short = plan.values.flatten().distinct().mapNotNull { id ->
            val item = state.item(id) ?: return@mapNotNull null
            val by = WeekPlan.shortBy(plan, id, item.step, state.stock(id))
            if (by > 0) item to by else null
        }.sortedBy { it.first.name.lowercase() }
        val currency by vm.currency.collectAsState()
        val weeklyBudget by vm.weeklyBudgetMinor.collectAsState()
        val weekFirst by vm.weekStartDay.collectAsState()
        val weekAnchor by vm.weekAnchorDay.collectAsState()
        val anchor = weekAnchor.takeIf { it > 0 }?.let { LocalDate.ofEpochDay(it) }
        val spent = Pantry.week(Pantry.weekStart(today, DayOfWeek.of(weekFirst), anchor), state.entriesByItem, isLong = { state.isLong(it) }, categoryOf = { id -> state.item(id)?.category ?: "" }).spentMinor
        val estimate = short.sumOf { (item, by) -> WeeklyBudget.estimateMinor(Recipes.unitPriceMinor(state.entries(item.id)), by) }
        val unpriced = short.count { Recipes.unitPriceMinor(state.entries(it.first.id)) == null }
        val toAdd = short.filter { !onShoppingList(it.first, state, today) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Text(
                    "Tap Add on a day to pick what you'll use. Below, you'll see what to buy.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "buy") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🛒  To buy for this plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        when {
                            plan.isEmpty() -> Text("Nothing planned yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            short.isEmpty() -> Text("✅  You have enough for everything planned.")
                            else -> {
                                short.forEach { (item, by) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name)
                                            Text(
                                                "Short by ${item.unitValue().label(by)} · planned ${WeekPlan.times(plan, item.id)}×, have ${item.unitValue().label(state.stock(item.id))}",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (onShoppingList(item, state, today)) Text("On list ✓", style = MaterialTheme.typography.bodySmall)
                                        else TextButton(onClick = { vm.setRefillNeeded(item, true) }) { Text("Add to shop") }
                                    }
                                }
                                Text(
                                    "Estimated cost: ${Money.format(estimate, currency)}" + if (unpriced > 0) " (+ $unpriced without a price yet)" else "",
                                    fontWeight = FontWeight.Medium,
                                )
                                if (weeklyBudget > 0) {
                                    val left = weeklyBudget - spent - estimate
                                    Text(
                                        if (left >= 0) "Fits your ${Money.format(weeklyBudget, currency)} weekly budget, ${Money.format(left, currency)} to spare."
                                        else "${Money.format(-left, currency)} over your ${Money.format(weeklyBudget, currency)} weekly budget. Drop something or plan cheaper.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (left >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                    )
                                }
                                val monthly by vm.budgetMinor.collectAsState()
                                if (monthly > 0) {
                                    val month = Pantry.spentBetween(state.entriesByItem.filterKeys { !state.isLong(it) }, today.withDayOfMonth(1), today)
                                    val mLeft = monthly - month - estimate
                                    Text(
                                        if (mLeft >= 0) "Month: ${Money.format(month, currency)} spent + this plan leaves ${Money.format(mLeft, currency)} of ${Money.format(monthly, currency)}."
                                        else "Month: this plan takes you ${Money.format(-mLeft, currency)} over ${Money.format(monthly, currency)}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (mLeft >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (toAdd.size > 1) {
                                    Button(onClick = { toAdd.forEach { vm.setRefillNeeded(it.first, true) } }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Add all ${toAdd.size} to shopping list")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(days, key = { it.toEpochDay() }) { day ->
                val ids = plan[day.toEpochDay()].orEmpty()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + day.dayOfMonth + " " + day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                                    if (day == today) " · Today" else "",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { picking = day.toEpochDay() }) { Text("＋ Add") }
                        }
                        if (ids.isEmpty()) {
                            Text("Nothing planned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            ids.mapNotNull { state.item(it) }.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { item ->
                                        FilterChip(selected = true, onClick = { vm.planToggle(day.toEpochDay(), item.id) }, label = { Text(item.name + "  ✕", maxLines = 1) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        picking?.let { dayEpoch ->
            var query by remember { mutableStateOf("") }
            val chosen = plan[dayEpoch].orEmpty()
            val list = state.items.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.sortedBy { it.name.lowercase() }
            AlertDialog(
                onDismissRequest = { picking = null },
                title = { Text("Plan for " + LocalDate.ofEpochDay(dayEpoch).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                text = {
                    Column {
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.padding(4.dp))
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(list, key = { it.id }) { item ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { vm.planToggle(dayEpoch, item.id) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = item.id in chosen, onCheckedChange = { vm.planToggle(dayEpoch, item.id) })
                                    Text(item.name, modifier = Modifier.weight(1f))
                                    Text(item.unitValue().label(state.stock(item.id)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { picking = null }) { Text("Done") } },
            )
        }
    }
}
