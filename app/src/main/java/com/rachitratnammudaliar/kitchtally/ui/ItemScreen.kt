@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.kitchtally.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.kitchtally.data.Event
import com.rachitratnammudaliar.kitchtally.data.PhotoStore
import com.rachitratnammudaliar.kitchtally.data.date
import com.rachitratnammudaliar.kitchtally.data.lifeValue
import com.rachitratnammudaliar.kitchtally.data.toEntry
import com.rachitratnammudaliar.kitchtally.data.typeValue
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Money
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.Qty
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import java.time.LocalDate

@Composable
fun ItemScreen(
    vm: PantryViewModel,
    state: PantryState?,
    id: Long,
    today: LocalDate,
    currency: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onBuy: (eventId: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var openEvent by remember { mutableStateOf<Event?>(null) }
    var showReceipt by remember { mutableStateOf("") }
    val item = state?.item(id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { confirmDelete = true }, enabled = item != null) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    IconButton(onClick = onEdit, enabled = item != null) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state == null) return@Scaffold
        if (item == null) {
            Text("This item was deleted.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val events = state.events(id)
        val entries = state.entries(id)
        val unit = item.unitValue()
        val stock = Pantry.stock(entries)
        val status = Pantry.status(entries, item.lowAt, today)
        val lastPriced = Pantry.lastPriced(entries)

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bitmap = remember(item.photoFile) { if (item.photoFile.isBlank()) null else PhotoStore.load(context, item.photoFile) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo of ${item.name}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bitmap == null) {
                    ItemImage(item.name, "", size = 72.dp, category = item.category)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    StockPill(stockLabel(item, stock) + "  ±", status) { adjusting = true }
                    if (item.packSize > 0) Hint(Pantry.packLabel(unit, item.packSize) + Pantry.packsHint(stock, item.packSize).let { if (it.isEmpty()) "" else " · $it left" })
                    Text(detailLine(item, state, today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Quick actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    vm.use(item, shared = false) { e, amount -> scope.undoSnackbar(snackbar, "Used ${unit.label(amount)}") { vm.undo(e) } }
                }, enabled = stock > 0, modifier = Modifier.weight(1f)) { Text("− Used") }
                OutlinedButton(onClick = {
                    vm.use(item, shared = true) { e, amount -> scope.undoSnackbar(snackbar, "Shared ${unit.label(amount)}") { vm.undo(e) } }
                }, enabled = stock > 0, modifier = Modifier.weight(1f)) { Text("Shared") }
                Button(onClick = {
                    vm.quickBuy(state, item) { buy -> scope.undoSnackbar(snackbar, "Added ${unit.label(buy.amount)}") { vm.undoBuy(buy) } }
                }, modifier = Modifier.weight(1f)) { Text("＋ Bought") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { adjusting = true }, modifier = Modifier.weight(1f)) { Text("Change amount…") }
                FilledTonalButton(onClick = { onBuy(0) }, modifier = Modifier.weight(1f)) { Text("Price & shop…") }
            }
            SwitchRow(
                title = "Refill needed",
                subtitle = if (item.refillNeeded) "On your shopping list" else "Put it on the shopping list now",
                checked = item.refillNeeded,
                onChange = { vm.setRefillNeeded(item, it) },
            )
            SwitchRow(
                title = "Not sure to buy again",
                subtitle = if (item.unsure) "Kept off the shopping list, even when it runs out" else "Keep it off the shopping list until you decide",
                checked = item.unsure,
                onChange = { vm.setUnsure(item, it) },
            )
            if (item.hiddenFromList) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("Hidden from the shopping list until you buy it again.")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.setHidden(item, false) }) { Text("Show again") }
                }
            }

            // Info
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    InfoRow("Category", item.category)
                    HorizontalDivider()
                    InfoRow("Lasts", item.lifeValue().label, item.lifeValue().description)
                    HorizontalDivider()
                    InfoRow("Running low at", unit.label(item.lowAt))
                    HorizontalDivider()
                    InfoRow("Each tap", "− uses ${unit.label(item.step)}", "＋ adds ${unit.label(Pantry.buyAmount(unit, entries, item.packSize))}")
                    Pantry.dailyUse(entries, today)?.let { rate ->
                        HorizontalDivider()
                        val perWeek = Qty.approx(rate * 7)
                        InfoRow("You use", "about $perWeek ${if (perWeek == "1") unit.singular else unit.plural} a week")
                    }
                    if (lastPriced != null) {
                        HorizontalDivider()
                        val unitPrice = Pantry.unitPriceMinor(lastPriced)
                        InfoRow(
                            "Last price",
                            unitPrice?.let { "${Money.format(it, currency)} ${unit.priceLabel}" } ?: "",
                            listOf(lastPriced.shop, lastPriced.date.format(dateFormat)).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                    }
                }
            }
            if (item.notes.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Notes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // History
            SectionLabel("History")
            if (events.isEmpty()) {
                Hint("Nothing logged yet. Tap ＋ Bought when you buy it.")
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        events.take(100).forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            HistoryRow(e, unit, currency) { openEvent = e }
                        }
                    }
                }
                if (events.size > 100) Hint("Showing the latest 100 of ${events.size}.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (adjusting && item != null && state != null) {
        AdjustDialog(item, state.stock(item.id), onDismiss = { adjusting = false }) { action, amount ->
            adjusting = false
            vm.adjust(state, item, action, amount) { message, undo -> scope.undoSnackbar(snackbar, message, undo) }
        }
    }

    openEvent?.let { e ->
        val type = e.typeValue()
        AlertDialog(
            onDismissRequest = { openEvent = null },
            title = { Text(historyTitle(e, item?.unitValue() ?: QtyUnit.PIECE)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.date().format(dateFormat))
                    e.priceMinor?.let { Text("Paid ${Money.format(it, currency)}" + if (e.shop.isNotBlank()) " at ${e.shop}" else "") }
                    if (e.priceMinor == null && e.shop.isNotBlank()) Text("At ${e.shop}")
                    if (e.note.isNotBlank()) Text(e.note)
                    if (e.receiptFile.isNotBlank()) TextButton(onClick = { showReceipt = e.receiptFile }) { Text("View receipt photo") }
                }
            },
            confirmButton = {
                if (type == EventType.BOUGHT) {
                    TextButton(onClick = { openEvent = null; onBuy(e.id) }) { Text("Edit") }
                } else {
                    TextButton(onClick = { openEvent = null }) { Text("Close") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.deleteEvent(e); openEvent = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    if (showReceipt.isNotBlank()) {
        val bitmap = remember(showReceipt) { PhotoStore.load(context, showReceipt) }
        AlertDialog(
            onDismissRequest = { showReceipt = "" },
            confirmButton = { TextButton(onClick = { showReceipt = "" }) { Text("Close") } },
            text = {
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Receipt", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("The photo couldn't be opened.")
                }
            },
        )
    }

    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${item.name}?") },
            text = { Text("This removes the item, its photos and its whole history. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteItem(item, state?.events(item.id).orEmpty())
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun historyTitle(e: Event, unit: QtyUnit): String {
    val qty = unit.label(kotlin.math.abs(e.quantity))
    return when (e.typeValue()) {
        EventType.BOUGHT -> "Bought $qty"
        EventType.USED -> "Used $qty"
        EventType.SHARED -> "Shared $qty with friends"
        EventType.ADJUST -> if (e.note.isNotBlank()) "${e.note}: ${if (e.quantity < 0) "−" else "+"}$qty" else "Stock changed by ${if (e.quantity < 0) "−" else "+"}$qty"
    }
}

@Composable
private fun HistoryRow(e: Event, unit: QtyUnit, currency: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(historyTitle(e, unit), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                listOf(e.date().format(dateFormat), e.shop).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        e.priceMinor?.let { Text(Money.format(it, currency), style = MaterialTheme.typography.bodyMedium) }
    }
}
