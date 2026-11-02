package com.rachitratnammudaliar.kitchtally.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Currency
import java.util.Locale

// Pure logic, no Android. The iOS app has a Swift port of this file with the same tests.

/** How an item is counted. Stored by [name]. */
enum class QtyUnit(val singular: String, val plural: String, val priceLabel: String) {
    PIECE("piece", "pieces", "each"),
    PACKET("packet", "packets", "per packet"),
    BOTTLE("bottle", "bottles", "per bottle"),
    BOX("box", "boxes", "per box"),
    BAG("bag", "bags", "per bag"),
    KG("kg", "kg", "per kg"),
    LITRE("litre", "litres", "per litre");

    fun label(qty: Double): String = "${Qty.format(qty)} ${if (qty == 1.0) singular else plural}"

    companion object {
        fun fromName(name: String): QtyUnit = entries.firstOrNull { it.name == name } ?: PIECE
    }
}

/** Roughly how long an item lasts once bought. Used for the "check it's fresh" hint. */
enum class Life(val label: String, val description: String, val freshDays: Long) {
    SHORT("Short", "Days, like bread or milk", 5),
    MEDIUM("Medium", "1 to 3 weeks, like onions or eggs", 21),
    LONG("Long", "Months, like rice, atta or pasta", 180);

    companion object {
        fun fromName(name: String): Life = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

enum class EventType {
    /** Bought or refilled; adds to stock. */
    BOUGHT,
    /** Used up; takes from stock. */
    USED,
    /** Used when cooking for friends; takes from stock, counted separately. Quantity only, never money. */
    SHARED,
    /** A correction or the starting stock; signed quantity. */
    ADJUST;

    companion object {
        fun fromName(name: String): EventType = entries.firstOrNull { it.name == name } ?: ADJUST
    }
}

enum class StockStatus { OUT, LOW, OK }

/** One logged change, independent of the database. */
data class Entry(
    val type: EventType,
    val date: LocalDate,
    val quantity: Double,
    val priceMinor: Long? = null,
    val shop: String = "",
)

object Categories {
    val presets = listOf(
        "Groceries", "Fruit & veg", "Bread & bakery", "Dairy & eggs", "Grains & flour",
        "Spices", "Snacks", "Drinks", "Toiletries", "Cleaning", "Other",
    )

    private val emoji = mapOf(
        "groceries" to "🛒", "fruit & veg" to "🥕", "bread & bakery" to "🍞", "dairy & eggs" to "🥛",
        "grains & flour" to "🌾", "spices" to "🌶️", "snacks" to "🍪", "drinks" to "🥤",
        "toiletries" to "🧴", "cleaning" to "🧽", "other" to "📦",
    )

    /** A picture for the category; the user's own categories get a jar. */
    fun emojiFor(category: String): String = emoji[category.trim().lowercase()] ?: "🫙"
}

object Shops {
    val presets = listOf("IKI", "Norfa", "Maxima", "Lidl", "Rimi", "Pepco")

    /** Presets first, then names the user typed before, without duplicates (ignoring case). */
    fun options(used: List<String>): List<String> {
        val all = presets + used.map { it.trim() }.filter { it.isNotEmpty() }
        return all.distinctBy { it.lowercase() }
    }
}

/** Quantities: up to 3 decimals, shown without trailing zeros. */
object Qty {
    fun round(q: Double): Double = BigDecimal.valueOf(q).setScale(3, RoundingMode.HALF_UP).toDouble()

    fun format(q: Double): String =
        BigDecimal.valueOf(round(q)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    /** Rounded for estimates: whole numbers from 2 up, otherwise one decimal. "about 3", "about 0.5". */
    fun approx(q: Double): String =
        if (q >= 2) Math.round(q).toString() else format(BigDecimal.valueOf(q).setScale(1, RoundingMode.HALF_UP).toDouble())

    /** "1", "0.5", "1,5" -> quantity. Null if not a positive number up to 10,000. */
    fun parse(text: String): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (v > 0 && v <= 10_000 && !v.isNaN()) round(v) else null
    }
}

object Pantry {

    fun stock(entries: List<Entry>): Double = Qty.round(
        entries.sumOf {
            when (it.type) {
                EventType.BOUGHT -> it.quantity
                EventType.ADJUST -> it.quantity
                EventType.USED, EventType.SHARED -> -it.quantity
            }
        },
    )

    fun lastBought(entries: List<Entry>): Entry? =
        entries.filter { it.type == EventType.BOUGHT }.maxByOrNull { it.date }

    /** The latest purchase that had a price, for pre-filling the next one. */
    fun lastPriced(entries: List<Entry>): Entry? =
        entries.filter { it.type == EventType.BOUGHT && it.priceMinor != null && it.quantity > 0 }.maxByOrNull { it.date }

    /** Price per unit (per kg, per packet...) of a purchase, rounded to the cent. */
    fun unitPriceMinor(e: Entry): Long? {
        val price = e.priceMinor ?: return null
        if (e.quantity <= 0) return null
        return BigDecimal.valueOf(price).divide(BigDecimal.valueOf(e.quantity), 0, RoundingMode.HALF_UP).toLong()
    }

    /** Total for a quantity at a unit price: 1.5 kg at €1.20/kg = €1.80. */
    fun totalMinor(unitPriceMinor: Long, quantity: Double): Long =
        BigDecimal.valueOf(unitPriceMinor).multiply(BigDecimal.valueOf(quantity)).setScale(0, RoundingMode.HALF_UP).toLong()

    /**
     * Average amount used per day over the last [windowDays], counted from the first use in that
     * window. Null until there are at least two uses at least two days apart.
     */
    fun dailyUse(entries: List<Entry>, today: LocalDate, windowDays: Long = 60): Double? {
        val from = today.minusDays(windowDays)
        val uses = entries.filter { (it.type == EventType.USED || it.type == EventType.SHARED) && !it.date.isBefore(from) && !it.date.isAfter(today) }
        if (uses.size < 2) return null
        val first = uses.minOf { it.date }
        val days = ChronoUnit.DAYS.between(first, today) + 1
        if (days < 2) return null
        return uses.sumOf { it.quantity } / days
    }

    /** About how many whole days the current stock will last, or null if it can't be estimated. */
    fun daysLeft(entries: List<Entry>, today: LocalDate): Long? {
        val stock = stock(entries)
        if (stock <= 0) return 0
        val rate = dailyUse(entries, today) ?: return null
        if (rate <= 0) return null
        return (stock / rate).toLong()
    }

    /** Out at zero; low at or below [lowAt], or when it's likely to run out within 2 days. */
    fun status(entries: List<Entry>, lowAt: Double, today: LocalDate): StockStatus {
        val stock = stock(entries)
        if (stock <= 0) return StockStatus.OUT
        if (stock <= lowAt) return StockStatus.LOW
        val left = daysLeft(entries, today)
        if (left != null && left <= 2) return StockStatus.LOW
        return StockStatus.OK
    }

    /** True when a short- or medium-life item was bought long enough ago to check it's still good. */
    fun checkFreshness(entries: List<Entry>, life: Life, today: LocalDate): Boolean {
        if (life == Life.LONG || stock(entries) <= 0) return false
        val last = lastBought(entries) ?: return false
        return ChronoUnit.DAYS.between(last.date, today) > life.freshDays
    }

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    data class ItemWeek(val itemId: Long, val used: Double, val shared: Double, val bought: Double, val spentMinor: Long)

    data class Week(
        val start: LocalDate,
        val items: List<ItemWeek>,
        val spentMinor: Long,
        val spentByCategory: Map<String, Long>,
        val usedTotal: Double,
        val sharedTotal: Double,
    )

    /**
     * Monday-to-Sunday summary. [entriesByItem] maps item id to its entries; [categoryOf] gives an
     * item's category. Used includes shared.
     */
    fun week(start: LocalDate, entriesByItem: Map<Long, List<Entry>>, categoryOf: (Long) -> String): Week {
        val end = start.plusDays(6)
        val items = entriesByItem.mapNotNull { (id, entries) ->
            val inWeek = entries.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            if (inWeek.isEmpty()) return@mapNotNull null
            val used = inWeek.filter { it.type == EventType.USED || it.type == EventType.SHARED }.sumOf { it.quantity }
            val shared = inWeek.filter { it.type == EventType.SHARED }.sumOf { it.quantity }
            val boughtEntries = inWeek.filter { it.type == EventType.BOUGHT }
            val week = ItemWeek(id, Qty.round(used), Qty.round(shared), Qty.round(boughtEntries.sumOf { it.quantity }), boughtEntries.sumOf { it.priceMinor ?: 0 })
            if (week.used == 0.0 && week.bought == 0.0) null else week
        }
        val byCategory = items.filter { it.spentMinor > 0 }
            .groupBy { categoryOf(it.itemId) }
            .mapValues { (_, l) -> l.sumOf { it.spentMinor } }
        return Week(
            start = start,
            items = items.sortedWith(compareByDescending<ItemWeek> { it.used }.thenByDescending { it.spentMinor }),
            spentMinor = items.sumOf { it.spentMinor },
            spentByCategory = byCategory,
            usedTotal = Qty.round(items.sumOf { it.used }),
            sharedTotal = Qty.round(items.sumOf { it.shared }),
        )
    }

    fun daysAgoLabel(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days < 0 -> "on ${date.dayOfMonth} ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
            days == 0L -> "today"
            days == 1L -> "yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            else -> "${days / 30} months ago"
        }
    }
}

/** Money is stored as whole cents to avoid rounding errors. */
object Money {
    val currencies = listOf("EUR", "INR", "USD", "GBP")

    /** "1.20", "1,2" or "3" -> cents. Null if invalid, negative or too large. Zero is allowed (free). */
    fun parseToMinor(text: String): Long? {
        val cleaned = text.trim().replace(" ", "").replace(',', '.')
        if (cleaned.isEmpty()) return null
        return try {
            val value = BigDecimal(cleaned)
            if (value.signum() < 0 || value.scale() > 2 || value > BigDecimal(1_000_000)) return null
            value.movePointRight(2).longValueExact()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    fun minorToInput(minor: Long): String = BigDecimal.valueOf(minor).movePointLeft(2).stripTrailingZeros().toPlainString()

    fun format(minor: Long, currencyCode: String): String {
        val locale = when (currencyCode) {
            "INR" -> Locale.forLanguageTag("en-IN")
            "EUR" -> Locale.forLanguageTag("en-IE")
            "GBP" -> Locale.UK
            else -> Locale.US
        }
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currencyCode) }
        return format.format(BigDecimal.valueOf(minor).movePointLeft(2))
    }
}

/** Colours and initials for items without a photo. */
object Badges {
    private val palette = listOf(
        0xFF2E7D32, 0xFF00897B, 0xFF6D4C41, 0xFFEF6C00, 0xFF3949AB, 0xFFAD1457, 0xFF00838F, 0xFF7B4DFF,
    )

    fun colorFor(name: String): Long = palette[Math.floorMod(name.trim().lowercase().hashCode(), palette.size)]

    fun initial(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
}
