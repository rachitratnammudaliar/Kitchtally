package com.rachitratnammudaliar.kitchtally.ui

import android.app.DatePickerDialog
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rachitratnammudaliar.kitchtally.data.PhotoStore
import com.rachitratnammudaliar.kitchtally.domain.Badges
import com.rachitratnammudaliar.kitchtally.domain.Categories
import com.rachitratnammudaliar.kitchtally.domain.StockStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Light = lightColorScheme(
    primary = Color(0xFF2E6B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6EFC4),
    onPrimaryContainer = Color(0xFF002109),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFFF7FAF4),
    surface = Color(0xFFF7FAF4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9BD69B),
    onPrimary = Color(0xFF003914),
    primaryContainer = Color(0xFF155224),
    onPrimaryContainer = Color(0xFFC6EFC4),
    secondary = Color(0xFFF4BC6B),
    secondaryContainer = Color(0xFF684300),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
)

@Composable
fun KitchtallyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Always Kitchtally's own green, not the phone's wallpaper colours.
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** The item's photo, or its category's picture on a soft tile. */
@Composable
fun ItemImage(name: String, photoFile: String, size: Dp = 48.dp, category: String? = null) {
    val context = LocalContext.current
    val bitmap = remember(photoFile) { if (photoFile.isBlank()) null else PhotoStore.load(context, photoFile) }
    val shape = RoundedCornerShape(size * 0.25f)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
        )
    } else if (category != null) {
        Box(
            modifier = Modifier.size(size).clip(shape).background(Color(Badges.colorFor(name.ifBlank { "?" })).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(Categories.emojiFor(category), fontSize = (size.value * 0.5f).sp)
        }
    } else {
        Box(
            modifier = Modifier.size(size).clip(shape).background(Color(Badges.colorFor(name.ifBlank { "?" }))),
            contentAlignment = Alignment.Center,
        ) {
            Text(Badges.initial(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        }
    }
}

/** A rounded label showing how much is left, coloured by status. */
@Composable
fun StockPill(text: String, status: StockStatus) {
    val dark = isSystemInDarkTheme()
    val (bg, fg) = when (status) {
        StockStatus.OUT -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        StockStatus.LOW -> (if (dark) Color(0xFF5A3D00) else Color(0xFFFFE2B0)) to (if (dark) Color(0xFFFFDDB3) else Color(0xFF5A3500))
        StockStatus.OK -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** A number with a label underneath, for summary cards. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val base = modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun statusColor(status: StockStatus): Color = when (status) {
    StockStatus.OUT -> MaterialTheme.colorScheme.error
    StockStatus.LOW -> if (isSystemInDarkTheme()) Color(0xFFF4BC6B) else Color(0xFF9A5B00)
    StockStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 4.dp))
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, sub: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.9f))
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

fun errorText(show: Boolean, message: String): (@Composable () -> Unit)? =
    if (show) {
        { Text(message) }
    } else {
        null
    }

@Composable
fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    error: String = "",
    placeholder: String = "",
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter { it.isDigit() || it == '.' || it == ',' }.take(10)) },
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        isError = isError,
        supportingText = errorText(isError && error.isNotEmpty(), error),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

fun pickDate(context: Context, date: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        date.year,
        date.monthValue - 1,
        date.dayOfMonth,
    ).show()
}
