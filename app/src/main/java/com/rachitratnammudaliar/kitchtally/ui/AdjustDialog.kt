package com.rachitratnammudaliar.kitchtally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.Qty

/**
 * Change any amount at once: −2, +2, or type a number, in pieces or in packs.
 * [onAction] gets the action and the amount in the item's unit.
 */
@Composable
fun AdjustDialog(item: Item, stock: Double, onDismiss: () -> Unit, onAction: (PantryViewModel.Adjust, Double) -> Unit) {
    val unit = item.unitValue()
    val packs = item.packSize > 0
    var inPacks by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("1") }
    val value = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it <= 10_000 }
    val amount = value?.let { Qty.round(if (inPacks) it * item.packSize else it) }
    fun step(by: Double) {
        val next = Qty.round(((value ?: 0.0) + by).coerceAtLeast(0.0))
        text = Qty.format(next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Now: ${stockLabel(item, stock)}" + Pantry.packsHint(stock, item.packSize).let { if (it.isEmpty()) "" else " ($it)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (packs) {
                    ChipRow(listOf(false, true), inPacks, { if (it) "Packs of ${Qty.format(item.packSize)}" else unit.plural.replaceFirstChar { c -> c.uppercase() } }, { inPacks = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { step(-2.0) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("−2") }
                    OutlinedButton(onClick = { step(-1.0) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("−1") }
                    NumberField(
                        value = text,
                        onChange = { text = it },
                        label = if (inPacks) "Packs" else unit.plural,
                        isError = value == null,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { step(1.0) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("+1") }
                    OutlinedButton(onClick = { step(2.0) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("+2") }
                }
                if (inPacks && amount != null) Hint("= ${unit.label(amount)}")
                val ok = amount != null && amount > 0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onAction(PantryViewModel.Adjust.USED, amount ?: 0.0) }, enabled = ok && stock > 0, modifier = Modifier.weight(1f)) { Text("Used") }
                    OutlinedButton(onClick = { onAction(PantryViewModel.Adjust.SHARED, amount ?: 0.0) }, enabled = ok && stock > 0, modifier = Modifier.weight(1f)) { Text("Shared") }
                    Button(onClick = { onAction(PantryViewModel.Adjust.BOUGHT, amount ?: 0.0) }, enabled = ok, modifier = Modifier.weight(1f)) { Text("Bought") }
                }
                FilledTonalButton(onClick = { onAction(PantryViewModel.Adjust.SET, amount ?: 0.0) }, enabled = amount != null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (amount != null) "Set what's left to ${unit.label(amount)}" else "Set what's left", textAlign = TextAlign.Center)
                }
                Hint("Used and Shared never take more than what's left.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
