@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.kitchtally.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.kitchtally.domain.Money
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: PantryViewModel, currency: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            snackbar.showSnackbar(
                try {
                    "Backup saved: ${vm.exportTo(uri)} items"
                } catch (e: Exception) {
                    "Couldn't save the backup: ${e.message ?: "unknown error"}"
                },
            )
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            snackbar.showSnackbar(
                try {
                    "Added ${vm.importFrom(uri)} items from the backup"
                } catch (e: Exception) {
                    e.message ?: "Couldn't read the backup"
                },
            )
        }
    }
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Currency")
            ChipRow(Money.currencies, currency, { it }, vm::setCurrency)
            Hint("Prices are shown in this currency. Changing it doesn't convert amounts you've already entered.")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Spending")
            val showSpending by vm.showSpending.collectAsStateWithLifecycle()
            val budget by vm.budgetMinor.collectAsStateWithLifecycle()
            SwitchRow("Show spending on Weekly", "Off keeps money out of sight until you tap Show spending", showSpending) { vm.setShowSpending(it) }
            var budgetText by rememberSaveable { mutableStateOf(if (budget > 0) Money.minorToInput(budget) else "") }
            val budgetValue = if (budgetText.isBlank()) 0L else Money.parseToMinor(budgetText)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    value = budgetText,
                    onChange = { budgetText = it },
                    label = "Monthly budget in $currency (optional)",
                    isError = budgetValue == null,
                    error = "A number, or leave it empty",
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = {
                    vm.setBudget(budgetValue ?: 0L)
                    scope.launch { snackbar.showSnackbar(if ((budgetValue ?: 0L) > 0) "Budget saved" else "Budget off") }
                }, enabled = budgetValue != null) { Text("Save") }
            }
            Hint("Only shown on Weekly, under Show spending. Leave it empty to turn it off.")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Backup")
            Text(
                "Save your items and history to a file you keep, and restore them on a new phone, Android or iPhone. Photos aren't included.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { exporter.launch("kitchtally-backup-${LocalDate.now()}.json") }) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Privacy")
            Text(
                "Kitchtally works completely offline. It has no internet permission, no account, no ads and no tracking. " +
                    "Your items, prices and photos stay on this phone and are excluded from cloud backups.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Kitchtally $version · github.com/rachitratnammudaliar/kitchtally",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
