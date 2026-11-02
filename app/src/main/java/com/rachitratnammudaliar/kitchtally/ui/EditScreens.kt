@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.kitchtally.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.kitchtally.data.Event
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.PhotoStore
import com.rachitratnammudaliar.kitchtally.data.unitValue
import com.rachitratnammudaliar.kitchtally.domain.Categories
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Life
import com.rachitratnammudaliar.kitchtally.domain.Money
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.Qty
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import com.rachitratnammudaliar.kitchtally.domain.Shops
import kotlinx.coroutines.launch
import java.time.LocalDate

/** "Take photo" and "Choose photo" buttons. Calls [onPicked] with the stored file name. */
@Composable
private fun PhotoButtons(
    vm: PantryViewModel,
    hasPhoto: Boolean,
    snackbar: SnackbarHostState,
    onPicked: (String) -> Unit,
    onRemove: () -> Unit,
    takeLabel: String = "Take photo",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    fun store(uri: Uri) {
        scope.launch {
            try {
                onPicked(vm.savePhoto(uri))
            } catch (e: Exception) {
                snackbar.showSnackbar("That photo couldn't be used. Try another one.")
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) store(uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) store(uri) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            try {
                val uri = PhotoStore.cameraUri(context)
                cameraUri = uri
                camera.launch(uri)
            } catch (e: Exception) {
                scope.launch { snackbar.showSnackbar("No camera app found") }
            }
        }) { Text(takeLabel) }
        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose") }
        if (hasPhoto) TextButton(onClick = onRemove) { Text("Remove") }
    }
}

/** Typed names, plus presets, as chips; "Other" lets you type a new one. */
@Composable
private fun NamedChoice(options: List<String>, selected: String, onSelect: (String) -> Unit, otherLabel: String, maxLength: Int) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf("") }
    val all = if (selected.isNotBlank() && options.none { it.equals(selected, ignoreCase = true) }) options + selected else options
    ChipRow(
        options = all + listOf("+ Other"),
        selected = if (typing) "+ Other" else all.firstOrNull { it.equals(selected, ignoreCase = true) },
        label = { it },
        onSelect = { choice ->
            if (choice == "+ Other") {
                typing = true
            } else {
                typing = false
                onSelect(if (choice == selected) "" else choice)
            }
        },
    )
    if (typing) {
        OutlinedTextField(
            value = custom,
            onValueChange = {
                custom = it.take(maxLength)
                onSelect(custom.trim())
            },
            label = { Text(otherLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ============================ Add / edit item ============================ */

@Composable
fun EditItemScreen(vm: PantryViewModel, state: PantryState?, id: Long, onDone: (savedId: Long) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val isNew = id == 0L
    val existing = state?.item(id)

    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Groceries") }
    var unit by rememberSaveable { mutableStateOf(QtyUnit.PACKET.name) }
    var life by rememberSaveable { mutableStateOf(Life.MEDIUM.name) }
    var lowAt by rememberSaveable { mutableStateOf("1") }
    var stepText by rememberSaveable { mutableStateOf("") }
    var packText by rememberSaveable { mutableStateOf("") }
    var stockText by rememberSaveable { mutableStateOf("") }
    var refill by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf("") }
    var originalPhoto by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        if (!loaded && existing != null) {
            name = existing.name
            category = existing.category
            unit = existing.unit
            life = existing.life
            lowAt = Qty.format(existing.lowAt)
            stepText = Qty.format(existing.step)
            packText = if (existing.packSize > 0) Qty.format(existing.packSize) else ""
            stockText = Qty.format(maxOf(0.0, state?.stock(existing.id) ?: 0.0))
            refill = existing.refillNeeded
            notes = existing.notes
            photo = existing.photoFile
            originalPhoto = existing.photoFile
            loaded = true
        }
    }

    val lowAtValue = lowAt.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it <= 10_000 }
    val stockValue = if (stockText.isBlank()) 0.0 else stockText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it <= 10_000 }
    val stepValue = if (stepText.isBlank()) Pantry.defaultStep(QtyUnit.fromName(unit)) else Qty.parse(stepText)
    val packValue = if (packText.isBlank()) 0.0 else Qty.parse(packText)
    val nameError = name.isBlank()
    val duplicate = name.isNotBlank() && state?.items?.any { it.id != id && it.name.trim().equals(name.trim(), ignoreCase = true) } == true
    val invalid = nameError || lowAtValue == null || stockValue == null || stepValue == null || packValue == null || category.isBlank()

    fun leave(savedId: Long) {
        if (savedId == 0L && photo.isNotBlank() && photo != originalPhoto) vm.discardPhoto(photo)
        onDone(savedId)
    }
    BackHandler { leave(0) }

    fun save() {
        if (invalid) {
            showErrors = true
            return
        }
        val item = (existing ?: Item(name = "")).copy(
            name = name.trim(),
            category = category.trim(),
            unit = unit,
            life = life,
            lowAt = Qty.round(lowAtValue ?: 1.0),
            refillNeeded = refill,
            notes = notes.trim(),
            photoFile = photo,
            step = stepValue ?: 1.0,
            packSize = packValue ?: 0.0,
        )
        val target = Qty.round(stockValue ?: 0.0)
        if (existing == null) {
            vm.saveItem(item, target, originalPhoto) { leave(it) }
        } else {
            // Changing the amount here logs a correction, so history stays right.
            val diff = Qty.round(target - maxOf(0.0, state?.stock(existing.id) ?: 0.0))
            if (diff != 0.0) vm.log(existing.id, EventType.ADJUST, diff)
            vm.saveItem(item, 0.0, originalPhoto) { leave(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New item" else "Edit item") },
                navigationIcon = { IconButton(onClick = { leave(0) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { save() }) { Text("Save") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemImage(name, photo, size = 64.dp, category = category)
                Spacer(Modifier.width(12.dp))
                PhotoButtons(
                    vm = vm,
                    hasPhoto = photo.isNotBlank(),
                    snackbar = snackbar,
                    onPicked = { newFile ->
                        if (photo.isNotBlank() && photo != originalPhoto) vm.discardPhoto(photo)
                        photo = newFile
                    },
                    onRemove = {
                        if (photo != originalPhoto) vm.discardPhoto(photo)
                        photo = ""
                    },
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Name") },
                placeholder = { Text("Basmati rice") },
                singleLine = true,
                isError = showErrors && nameError,
                supportingText = when {
                    showErrors && nameError -> errorText(true, "Enter a name")
                    duplicate -> errorText(true, "You already have an item called this. You can still save it.")
                    else -> null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Category")
            NamedChoice(
                options = (Categories.presets + (state?.usedCategories ?: emptyList())).distinctBy { it.lowercase() },
                selected = category,
                onSelect = { category = it },
                otherLabel = "Category name",
                maxLength = 40,
            )
            if (showErrors && category.isBlank()) Hint("Choose or type a category", error = true)

            SectionLabel("Counted in")
            ChipRow(QtyUnit.entries, QtyUnit.fromName(unit), { it.plural.replaceFirstChar { c -> c.uppercase() } }, { unit = it.name })

            SectionLabel("How long it lasts")
            ChipRow(Life.entries, Life.fromName(life), { it.label }, { life = it.name })
            Hint(Life.fromName(life).description)

            val unitValue = QtyUnit.fromName(unit)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    value = stockText,
                    onChange = { stockText = it },
                    label = if (isNew) "You have now" else "Amount now",
                    placeholder = "0",
                    isError = showErrors && stockValue == null,
                    error = "A number, 0 or more",
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = lowAt,
                    onChange = { lowAt = it },
                    label = "Running low at",
                    isError = showErrors && lowAtValue == null,
                    error = "A number, 0 or more",
                    modifier = Modifier.weight(1f),
                )
            }
            Hint("In ${unitValue.plural}. It goes on the shopping list at or below \"running low at\".")
            NumberField(
                value = stepText,
                onChange = { stepText = it },
                label = "Each − or Shared uses",
                placeholder = Qty.format(Pantry.defaultStep(unitValue)),
                isError = showErrors && stepValue == null,
                error = "An amount above 0",
            )
            Hint("For example 0.5 kg of rice per meal, or 1 packet of bread. Leave it empty for ${unitValue.label(Pantry.defaultStep(unitValue))}.")
            NumberField(
                value = packText,
                onChange = { packText = it },
                label = "Comes in packs of (optional)",
                placeholder = "e.g. 10",
                isError = showErrors && packValue == null,
                error = "An amount above 0, or leave it empty",
            )
            Hint("Eggs: count them in pieces and set packs of 10. Then ＋ adds a whole pack and − takes one egg.")

            SwitchRow("Refill needed", "Put it on the shopping list now", refill) { refill = it }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(500) },
                label = { Text("Notes (optional)") },
                placeholder = { Text("Brand, size, where it's kept…") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ============================ Log or edit a purchase ============================ */

@Composable
fun PurchaseScreen(vm: PantryViewModel, state: PantryState?, itemId: Long, eventId: Long, today: LocalDate, currency: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val item = state?.item(itemId)
    val existing = state?.events(itemId)?.firstOrNull { it.id == eventId }

    var loaded by rememberSaveable { mutableStateOf(false) }
    var qtyText by rememberSaveable { mutableStateOf("1") }
    var inPacks by rememberSaveable { mutableStateOf(true) }
    var day by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var perUnit by rememberSaveable { mutableStateOf(false) }
    var priceText by rememberSaveable { mutableStateOf("") }
    var shop by rememberSaveable { mutableStateOf("") }
    var receipt by rememberSaveable { mutableStateOf("") }
    var originalReceipt by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(item?.id, existing?.id) {
        if (loaded || item == null || state == null) return@LaunchedEffect
        if (existing != null) {
            inPacks = false
            qtyText = Qty.format(existing.quantity)
            day = existing.epochDay
            priceText = existing.priceMinor?.let { Money.minorToInput(it) } ?: ""
            shop = existing.shop
            receipt = existing.receiptFile
            originalReceipt = existing.receiptFile
            note = existing.note
        } else {
            // Pre-fill with last time's unit price and shop.
            val entries = state.entries(itemId)
            val last = Pantry.lastPriced(entries)
            shop = last?.shop?.takeIf { it.isNotBlank() } ?: Pantry.lastBought(entries)?.shop ?: ""
            val unitPrice = last?.let { Pantry.unitPriceMinor(it) }
            if (item.packSize > 0) {
                inPacks = true
                qtyText = "1"
                // Pre-fill what one pack cost last time.
                Pantry.priceFor(entries, item.packSize)?.let { priceText = Money.minorToInput(it) }
            } else if (unitPrice != null) {
                // For one unit, the total and the unit price are the same.
                perUnit = item.unitValue() == QtyUnit.KG || item.unitValue() == QtyUnit.LITRE
                priceText = Money.minorToInput(unitPrice)
            }
        }
        loaded = true
    }

    val packMode = item != null && item.packSize > 0 && inPacks
    val entered = Qty.parse(qtyText)
    /** Always in the item's unit (pieces), whatever was typed. */
    val qty = entered?.let { if (packMode) Qty.round(it * (item?.packSize ?: 1.0)) else it }
    val price = if (priceText.isBlank()) null else Money.parseToMinor(priceText)
    val priceError = priceText.isNotBlank() && price == null
    val total = when {
        price == null || qty == null -> null
        perUnit -> Pantry.totalMinor(price, entered ?: qty)
        else -> price
    }

    fun leave(saved: Boolean) {
        if (!saved && receipt.isNotBlank() && receipt != originalReceipt) vm.discardPhoto(receipt)
        onDone()
    }
    BackHandler { leave(false) }

    fun save() {
        if (qty == null || priceError || item == null) {
            showErrors = true
            return
        }
        vm.saveEvent(
            Event(
                id = existing?.id ?: 0,
                itemId = itemId,
                type = EventType.BOUGHT.name,
                epochDay = day,
                quantity = qty,
                priceMinor = total,
                shop = shop.trim(),
                receiptFile = receipt,
                note = note.trim(),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ),
            replacedReceipt = originalReceipt,
        )
        leave(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Bought ${item?.name ?: ""}" else "Edit purchase") },
                navigationIcon = { IconButton(onClick = { leave(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { save() }) { Text("Save") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (item == null || !loaded) return@Scaffold
        val unit = item.unitValue()
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.packSize > 0) {
                ChipRow(listOf(true, false), inPacks, { if (it) "Packs of ${Qty.format(item.packSize)}" else unit.plural.replaceFirstChar { c -> c.uppercase() } }, { inPacks = it })
            }
            NumberField(
                value = qtyText,
                onChange = { qtyText = it },
                label = if (packMode) "How many packs" else "How many ${unit.plural}",
                isError = showErrors && qty == null,
                error = "Enter an amount above 0",
            )
            if (packMode && qty != null) Hint("= ${unit.label(qty)}")
            OutlinedButton(onClick = { pickDate(context, LocalDate.ofEpochDay(day), max = today) { day = it.toEpochDay() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Bought on ${LocalDate.ofEpochDay(day).format(dateFormat)}")
            }

            SectionLabel("Price (optional)")
            val perLabel = if (packMode) "per pack" else unit.priceLabel
            ChipRow(listOf(false, true), perUnit, { if (it) "Price $perLabel" else "Total paid" }, { perUnit = it })
            NumberField(
                value = priceText,
                onChange = { priceText = it },
                label = if (perUnit) "$currency $perLabel" else "Total in $currency",
                isError = showErrors && priceError,
                error = "Enter a price, up to 2 decimals",
            )
            if (perUnit && total != null && entered != null) {
                Hint("${Qty.format(entered)} × ${Money.format(price ?: 0, currency)} = ${Money.format(total, currency)}")
            } else if (!perUnit && total != null && qty != null && qty != 1.0) {
                Hint("${Money.format(Pantry.totalMinor(total, 1.0 / qty), currency)} ${unit.priceLabel}")
            }

            SectionLabel("Shop")
            NamedChoice(
                options = Shops.options(state?.usedShops.orEmpty()),
                selected = shop,
                onSelect = { shop = it },
                otherLabel = "Shop name",
                maxLength = 40,
            )

            SectionLabel("Receipt photo (optional)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (receipt.isNotBlank()) {
                    ItemImage("R", receipt, size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                }
                PhotoButtons(
                    vm = vm,
                    hasPhoto = receipt.isNotBlank(),
                    snackbar = snackbar,
                    onPicked = { newFile ->
                        if (receipt.isNotBlank() && receipt != originalReceipt) vm.discardPhoto(receipt)
                        receipt = newFile
                    },
                    onRemove = {
                        if (receipt != originalReceipt) vm.discardPhoto(receipt)
                        receipt = ""
                    },
                    takeLabel = "Photo",
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text("Note (optional)") },
                placeholder = { Text("On offer, new brand…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Saving adds ${qty?.let { unit.label(it) } ?: "it"} to your stock" + if (item.refillNeeded) " and takes it off the shopping list." else ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
