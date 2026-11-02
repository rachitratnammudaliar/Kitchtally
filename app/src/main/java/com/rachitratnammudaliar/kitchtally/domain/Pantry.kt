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
        "Vegetables", "Fruit", "Staples", "Sauces & spreads", "Dairy & eggs", "Meat & protein",
        "Bread & bakery", "Spices", "Snacks", "Drinks", "Personal care", "Household", "Other",
    )

    /** Names used before version 1.2, and what they're called now. */
    val renamed = mapOf(
        "Fruit & veg" to "Vegetables",
        "Grains & flour" to "Staples",
        "Toiletries" to "Personal care",
        "Cleaning" to "Household",
    )

    private val emoji = mapOf(
        "vegetables" to "🥬", "fruit" to "🍎", "staples" to "🌾", "sauces & spreads" to "🥫",
        "dairy & eggs" to "🥛", "meat & protein" to "🍗", "bread & bakery" to "🍞", "spices" to "🌶️",
        "snacks" to "🍪", "drinks" to "🥤", "personal care" to "🧴", "household" to "🧻", "other" to "📦",
        "groceries" to "🛒",
    )

    /** A picture for the category; the user's own categories get a jar. */
    fun emojiFor(category: String): String = emoji[category.trim().lowercase()] ?: "🫙"
}

/** What a new item probably is, from its name: category, how it's counted, how long it lasts. */
data class Suggestion(val category: String, val unit: QtyUnit, val life: Life, val packSize: Double = 0.0)

object Suggestions {
    private fun s(category: String, unit: QtyUnit, life: Life, packSize: Double = 0.0) = Suggestion(category, unit, life, packSize)

    // Checked in order, so more specific names come first: "garlic sauce" is a sauce,
    // "eggplant" a vegetable, "hair oil" personal care, "peanut butter" a spread.
    private val rules: List<Pair<List<String>, Suggestion>> = listOf(
        listOf("ice cream") to s("Snacks", QtyUnit.PACKET, Life.MEDIUM),
        listOf("toilet paper", "toilet roll", "tissue", "kitchen roll", "paper towel", "napkin") to s("Household", QtyUnit.PACKET, Life.LONG),
        listOf("dishwash", "dish wash", "dish soap", "washing liquid", "washing up", "detergent", "laundry", "cleaner", "bleach", "sponge", "scrub", "bin bag", "trash bag", "garbage bag", "foil", "cling", "baking paper") to s("Household", QtyUnit.BOTTLE, Life.LONG),
        listOf("body wash", "shower gel", "soap", "shampoo", "conditioner", "toothpaste", "toothbrush", "mouthwash", "face wash", "facewash", "cleanser", "body cream", "face cream", "hand cream", "night cream", "day cream", "lotion", "moisturiser", "moisturizer", "sunscreen", "deodorant", "deo", "razor", "shaving", "lip balm", "hair oil", "hair gel", "sanitary", "pads", "cotton") to s("Personal care", QtyUnit.PIECE, Life.LONG),
        listOf("sauce", "puree", "purée", "ketchup", "mayo", "mayonnaise", "jam", "pickle", "achar", "chutney", "dressing", "honey", "peanut butter", "nutella", "spread", "tomato paste", "ginger garlic paste", "salsa", "pesto") to s("Sauces & spreads", QtyUnit.BOTTLE, Life.LONG),
        listOf("veggie", "vegetable", "frozen mix", "mushroom", "spinach", "salad", "lettuce", "herbs", "coriander leaves", "mint") to s("Vegetables", QtyUnit.PACKET, Life.SHORT),
        listOf("onion", "potato", "garlic", "ginger", "pumpkin") to s("Vegetables", QtyUnit.KG, Life.MEDIUM),
        listOf("carrot", "tomato", "cucumber", "capsicum", "bell pepper", "cabbage", "cauliflower", "broccoli", "green peas", "beans", "zucchini", "courgette", "eggplant", "aubergine", "brinjal", "beetroot", "radish", "okra", "bhindi", "chilli", "chili") to s("Vegetables", QtyUnit.KG, Life.SHORT),
        listOf("apple", "banana", "orange", "grape", "mango", "kiwi", "pear", "berr", "strawberr", "blueberr", "raspberr", "lemon", "lime", "melon", "pineapple", "avocado", "pomegranate", "plum", "peach") to s("Fruit", QtyUnit.PIECE, Life.SHORT),
        listOf("egg") to s("Dairy & eggs", QtyUnit.PIECE, Life.MEDIUM, 10.0),
        listOf("milk", "substitute", "oat drink", "soy drink", "almond drink", "oatly") to s("Dairy & eggs", QtyUnit.BOTTLE, Life.SHORT),
        listOf("yogurt", "yoghurt", "curd", "dahi", "kefir", "skyr") to s("Dairy & eggs", QtyUnit.PIECE, Life.SHORT),
        listOf("cheese", "butter", "ghee", "paneer", "cream") to s("Dairy & eggs", QtyUnit.PACKET, Life.MEDIUM),
        listOf("chicken", "fish", "meat", "mutton", "lamb", "beef", "pork", "turkey", "salmon", "tuna", "prawn", "shrimp", "tofu", "sausage", "ham", "keema", "mince") to s("Meat & protein", QtyUnit.PACKET, Life.SHORT),
        listOf("bread", "bun", "roti", "naan", "tortilla", "wrap", "croissant", "bagel", "pav") to s("Bread & bakery", QtyUnit.PACKET, Life.SHORT),
        listOf("masala", "spice", "turmeric", "haldi", "cumin", "jeera", "chilli powder", "chili powder", "pepper", "garam", "coriander powder", "cinnamon", "oregano", "paprika", "seasoning", "cardamom", "clove", "mustard seed", "hing") to s("Spices", QtyUnit.PACKET, Life.LONG),
        listOf("rice", "atta", "flour", "maida", "besan", "suji", "sooji", "semolina", "poha", "quinoa") to s("Staples", QtyUnit.KG, Life.LONG),
        listOf("oil") to s("Staples", QtyUnit.BOTTLE, Life.LONG),
        listOf("pasta", "spaghetti", "spagheti", "macaroni", "penne", "noodle", "maggi", "oats", "chole", "chana", "chickpea", "rajma", "dal", "daal", "lentil", "salt", "sugar", "cereal", "cornflakes", "muesli", "vermicelli") to s("Staples", QtyUnit.PACKET, Life.LONG),
        listOf("coffee", "tea", "chai") to s("Drinks", QtyUnit.PACKET, Life.LONG),
        listOf("juice", "water", "soda", "cola", "coke", "sprite", "beer", "wine", "energy drink", "lassi") to s("Drinks", QtyUnit.BOTTLE, Life.MEDIUM),
        listOf("chips", "crisps", "biscuit", "cookie", "chocolate", "namkeen", "bhujia", "popcorn", "nuts", "almond", "cashew", "peanut", "candy", "sweets", "cake") to s("Snacks", QtyUnit.PACKET, Life.LONG),
    )

    /** True if [keyword] starts a word in [text]: "egg" matches "Eggs" but "peas" doesn't match "chickpeas". */
    private fun startsWord(text: String, keyword: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(keyword, from)
            if (i < 0) return false
            if (i == 0 || !text[i - 1].isLetter()) return true
            from = i + 1
        }
    }

    fun forName(name: String): Suggestion? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        return rules.firstOrNull { (keys, _) -> keys.any { startsWord(n, it) } }?.second
    }
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

    /** Default "uses per tap": half a kilo or litre, otherwise one. */
    fun defaultStep(unit: QtyUnit): Double = if (unit == QtyUnit.KG || unit == QtyUnit.LITRE) 0.5 else 1.0

    /** What one tap of − or Shared takes: the item's step, never more than what's left. */
    fun useAmount(step: Double, stock: Double): Double = Qty.round(minOf(step.coerceAtLeast(0.001), stock)).coerceAtLeast(0.0)

    /**
     * What one tap of ＋ adds: a whole pack if the item comes in packs (10 eggs); for kilos and
     * litres, the size you usually buy (the last purchase), so a 5 kg bag of rice adds 5 kg;
     * otherwise one.
     */
    fun buyAmount(unit: QtyUnit, entries: List<Entry>, packSize: Double = 0.0): Double = when {
        packSize > 0 -> packSize
        unit == QtyUnit.KG || unit == QtyUnit.LITRE -> lastBought(entries)?.quantity?.takeIf { it > 0 } ?: 1.0
        else -> 1.0
    }

    /** The expected price of [buyAmount] at the last price paid, or null if no price is known. */
    fun expectedPriceMinor(unit: QtyUnit, entries: List<Entry>, packSize: Double = 0.0): Long? =
        priceFor(entries, buyAmount(unit, entries, packSize))

    /**
     * The price of [quantity] at the last price paid, worked out from the whole purchase and
     * rounded once: a pack of 10 eggs for €2.89 is €2.89, not 10 × €0.29.
     */
    fun priceFor(entries: List<Entry>, quantity: Double): Long? {
        val last = lastPriced(entries) ?: return null
        val paid = last.priceMinor ?: return null
        return BigDecimal.valueOf(paid).multiply(BigDecimal.valueOf(quantity))
            .divide(BigDecimal.valueOf(last.quantity), 0, RoundingMode.HALF_UP).toLong()
    }

    /** "1 pack = 10 pieces", or "" if not bought in packs. */
    fun packLabel(unit: QtyUnit, packSize: Double): String =
        if (packSize > 0) "1 pack = ${unit.label(packSize)}" else ""

    /** "8 pieces (about 1 pack)" style extra for pack items, or "". */
    fun packsHint(stock: Double, packSize: Double): String {
        if (packSize <= 0 || stock <= 0) return ""
        val packs = stock / packSize
        val text = if (packs >= 2) Math.round(packs).toString() else Qty.format(BigDecimal.valueOf(packs).setScale(1, RoundingMode.HALF_UP).toDouble())
        return "about $text pack${if (text == "1") "" else "s"}"
    }

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
        /** Different items used (amounts in different units can't be added up). */
        val itemsUsed: Int,
        /** How many times something was shared. */
        val timesShared: Int,
        /** Different items bought. */
        val itemsBought: Int,
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
        val timesShared = entriesByItem.values.sumOf { entries ->
            entries.count { it.type == EventType.SHARED && !it.date.isBefore(start) && !it.date.isAfter(end) }
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
            itemsUsed = items.count { it.used > 0 },
            timesShared = timesShared,
            itemsBought = items.count { it.bought > 0 },
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
