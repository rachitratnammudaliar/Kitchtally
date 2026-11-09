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
enum class QtyUnit(
    val singular: String,
    val plural: String,
    val priceLabel: String,
    /** Name on the "Counted in" chips. */
    val title: String,
    /** Prices are shown per kg or litre, so grams and millilitres divide by 1000. */
    val priceDivisor: Int = 1,
) {
    PIECE("piece", "pieces", "each", "Pieces"),
    PACKET("packet", "packets", "per packet", "Packets"),
    BOTTLE("bottle", "bottles", "per bottle", "Bottles"),
    BOX("box", "boxes", "per box", "Boxes"),
    BAG("bag", "bags", "per bag", "Bags"),
    /** How full it is: 1 = one full packet or bottle, 0.25 = a quarter left. */
    LEVEL("full one", "full ones", "each", "% left"),
    GRAM("g", "g", "per kg", "Grams", 1000),
    KG("kg", "kg", "per kg", "Kg"),
    ML("ml", "ml", "per litre", "Ml", 1000),
    LITRE("litre", "litres", "per litre", "Litres");

    val isLevel: Boolean get() = this == LEVEL
    val byWeight: Boolean get() = this == GRAM || this == KG || this == ML || this == LITRE

    /** An amount: "2 packets", "500 g", and for % items "25%", "1 full", "1 full + 50%". */
    fun label(qty: Double): String {
        if (!isLevel) {
            if (!byWeight) {
                // People say "half a packet" and "a little", not 0.5 or 0.1.
                if (qty > 0 && qty < 0.2) return "a little"
                Qty.fraction(qty)?.let { return "$it ${if (qty <= 1.0) singular else plural}" }
            }
            return "${Qty.format(qty)} ${if (qty == 1.0) singular else plural}"
        }
        val whole = Math.floor(Qty.round(qty) + 1e-9).toInt()
        val pct = Qty.percent(qty - whole)
        return when {
            whole == 0 -> "$pct%"
            pct == 0 -> "$whole full"
            else -> "$whole full + $pct%"
        }
    }

    /** What's left: "2 packets left", "80% left", "Full", "2 full". */
    fun leftLabel(stock: Double): String = when {
        !isLevel -> "${label(stock)} left"
        Qty.round(stock) == 1.0 -> "Full"
        stock < 1 -> "${label(stock)} left"
        else -> label(stock)
    }

    companion object {
        fun fromName(name: String): QtyUnit = entries.firstOrNull { it.name == name } ?: PIECE
    }
}

/** Roughly how long an item lasts once bought. Used for the "check it's fresh" hint. */
enum class Life(val label: String, val description: String, val freshDays: Long) {
    SHORT("Short", "2 to 3 days, like bread or milk", 3),
    MEDIUM("Medium", "1 week to 10 days, like vegetables or eggs", 10),
    LONG("Long", "2 weeks to a month or more, like rice, salt or oil. No freshness reminder", 30);

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

/** OUT: none left. LAST: only one meal left (red). LOW: running low. OK: plenty. */
enum class StockStatus { OUT, LAST, LOW, OK }

/** How an item is counted and when it's low, independent of the database. */
data class Counting(
    val unit: QtyUnit = QtyUnit.PIECE,
    val lowAt: Double = 1.0,
    /** Meals one unit makes (one full packet for % items); 0 if not counting meals. */
    val mealsPerUnit: Double = 0.0,
    /** [lowAt] is in meals instead of the item's unit. */
    val lowInMeals: Boolean = false,
) {
    val countsMeals: Boolean get() = mealsPerUnit > 0
}

object Meals {
    fun left(stock: Double, mealsPerUnit: Double): Double = Qty.round(maxOf(0.0, stock) * mealsPerUnit)

    /** "about 6 meals", "1 to 2 meals", "1 meal", "less than 1 meal", "no meals". */
    fun label(meals: Double): String {
        val whole = Math.floor(Qty.round(meals) + 1e-9).toLong()
        return when {
            meals <= 0 -> "no meals"
            whole == 0L -> "less than 1 meal"
            whole == 1L && Qty.round(meals) > 1.0 -> "1 to 2 meals"
            whole == 1L -> "1 meal"
            else -> "about $whole meals"
        }
    }

    /** What cooking one meal takes: one meal's worth when meals are counted, otherwise one tap. */
    fun cookAmount(step: Double, mealsPerUnit: Double): Double =
        if (mealsPerUnit > 0) Qty.round(1.0 / mealsPerUnit).coerceAtLeast(0.001) else step

    /** Meals per unit from "these 5 onions make about 8 meals". Null if either is missing. */
    fun perUnit(quantity: Double, meals: Double): Double? =
        if (quantity > 0 && meals > 0) BigDecimal.valueOf(meals).divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP).toDouble() else null
}

/** One logged change, independent of the database. */
data class Entry(
    val type: EventType,
    val date: LocalDate,
    val quantity: Double,
    val priceMinor: Long? = null,
    val shop: String = "",
    /** A purchase made for next week counts from this day on the Weekly tab. */
    val countsFrom: LocalDate? = null,
    /** Bought on a craving: an avoidable expense. */
    val craving: Boolean = false,
) {
    /** The day this entry counts on the Weekly tab: purchases may be moved to next week. */
    val weekDate: LocalDate get() = if (type == EventType.BOUGHT) countsFrom ?: date else date
}

object Categories {
    val presets = listOf(
        "Vegetables", "Fruit", "Staples", "Sauces & spreads", "Dairy & eggs", "Meat & protein",
        "Bread & bakery", "Spices", "Snacks", "Drinks", "Personal care", "Household", "Other",
    )

    /** Things that last a month or more and aren't food: counted in Budget, not in the weekly shop. */
    fun isLongLasting(category: String): Boolean = category.equals("Personal care", ignoreCase = true) || category.equals("Household", ignoreCase = true)

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
data class Suggestion(val category: String, val unit: QtyUnit, val life: Life, val packSize: Double = 0.0, val countMeals: Boolean = false)

object Suggestions {
    private fun s(category: String, unit: QtyUnit, life: Life, packSize: Double = 0.0, meals: Boolean = false) = Suggestion(category, unit, life, packSize, meals)

    // Checked in order, so more specific names come first: "garlic sauce" is a sauce,
    // "eggplant" a vegetable, "hair oil" personal care, "peanut butter" a spread.
    private val rules: List<Pair<List<String>, Suggestion>> = listOf(
        listOf("ice cream") to s("Snacks", QtyUnit.LEVEL, Life.LONG),
        listOf("toilet paper", "toilet roll", "tissue", "kitchen roll", "paper towel", "napkin") to s("Household", QtyUnit.PACKET, Life.LONG),
        listOf("sponge", "scrub", "bin bag", "trash bag", "garbage bag", "foil", "cling", "baking paper") to s("Household", QtyUnit.PACKET, Life.LONG),
        listOf("dishwash", "dish wash", "dish soap", "washing liquid", "washing up", "detergent", "laundry", "cleaner", "bleach") to s("Household", QtyUnit.LEVEL, Life.LONG),
        listOf("toothbrush", "razor", "shaving", "sanitary", "pads", "cotton") to s("Personal care", QtyUnit.PIECE, Life.LONG),
        listOf("body wash", "shower gel", "soap", "shampoo", "conditioner", "toothpaste", "mouthwash", "face wash", "facewash", "cleanser", "body cream", "face cream", "hand cream", "night cream", "day cream", "lotion", "moisturiser", "moisturizer", "sunscreen", "deodorant", "deo", "lip balm", "perfume", "cologne", "fragrance", "body spray", "hair oil", "hair gel") to s("Personal care", QtyUnit.LEVEL, Life.LONG),
        listOf("sauce", "puree", "purée", "ketchup", "mayo", "mayonnaise", "jam", "pickle", "achar", "chutney", "dressing", "honey", "peanut butter", "nutella", "spread", "tomato paste", "ginger garlic paste", "salsa", "pesto") to s("Sauces & spreads", QtyUnit.LEVEL, Life.LONG),
        listOf("veggie", "vegetable", "frozen mix", "mushroom", "spinach", "salad", "lettuce", "herbs", "coriander leaves", "mint") to s("Vegetables", QtyUnit.LEVEL, Life.MEDIUM, meals = true),
        listOf("chilli powder", "chili powder", "chilli flakes", "chili flakes") to s("Spices", QtyUnit.LEVEL, Life.LONG),
        listOf("garlic", "ginger") to s("Vegetables", QtyUnit.PIECE, Life.LONG),
        listOf("onion", "potato", "carrot", "tomato", "cucumber", "capsicum", "bell pepper", "cabbage", "cauliflower", "broccoli", "pumpkin", "green peas", "beans", "zucchini", "courgette", "eggplant", "aubergine", "brinjal", "beetroot", "radish", "okra", "bhindi", "chilli", "chili") to s("Vegetables", QtyUnit.PIECE, Life.MEDIUM, meals = true),
        listOf("apple", "banana", "orange", "grape", "mango", "kiwi", "pear", "berr", "strawberr", "blueberr", "raspberr", "lemon", "lime", "melon", "pineapple", "avocado", "pomegranate", "plum", "peach") to s("Fruit", QtyUnit.PIECE, Life.MEDIUM),
        listOf("egg") to s("Dairy & eggs", QtyUnit.PIECE, Life.MEDIUM, 10.0, meals = true),
        listOf("milk", "substitute", "oat drink", "soy drink", "almond drink", "oatly") to s("Dairy & eggs", QtyUnit.LEVEL, Life.SHORT),
        listOf("yogurt", "yoghurt", "curd", "dahi", "kefir", "skyr") to s("Dairy & eggs", QtyUnit.PIECE, Life.MEDIUM),
        listOf("cheese", "butter", "ghee", "paneer", "cream") to s("Dairy & eggs", QtyUnit.LEVEL, Life.MEDIUM),
        listOf("chicken", "fish", "meat", "mutton", "lamb", "beef", "pork", "turkey", "salmon", "tuna", "prawn", "shrimp", "tofu", "sausage", "ham", "keema", "mince") to s("Meat & protein", QtyUnit.PACKET, Life.SHORT, meals = true),
        listOf("bread", "bun", "roti", "naan", "tortilla", "wrap", "croissant", "bagel", "pav") to s("Bread & bakery", QtyUnit.LEVEL, Life.SHORT),
        listOf("masala", "spice", "turmeric", "haldi", "cumin", "jeera", "chilli powder", "chili powder", "pepper", "garam", "coriander powder", "cinnamon", "oregano", "paprika", "seasoning", "cardamom", "clove", "mustard seed", "hing") to s("Spices", QtyUnit.LEVEL, Life.LONG),
        listOf("rice", "atta", "flour", "maida", "besan", "suji", "sooji", "semolina", "poha", "quinoa") to s("Staples", QtyUnit.KG, Life.LONG, meals = true),
        listOf("oil") to s("Staples", QtyUnit.LEVEL, Life.LONG),
        listOf("salt", "sugar") to s("Staples", QtyUnit.LEVEL, Life.LONG),
        listOf("oats", "cereal", "cornflakes", "muesli") to s("Staples", QtyUnit.LEVEL, Life.LONG, meals = true),
        listOf("pasta", "spaghetti", "spagheti", "macaroni", "penne", "noodle", "maggi", "chole", "chana", "chickpea", "rajma", "dal", "daal", "lentil", "vermicelli") to s("Staples", QtyUnit.PACKET, Life.LONG, meals = true),
        listOf("coffee", "tea", "chai") to s("Drinks", QtyUnit.LEVEL, Life.LONG),
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

    /** 0.8 -> 80. */
    fun percent(fraction: Double): Int = BigDecimal.valueOf(fraction).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt()

    /** ¼ ½ ¾ and "1½", for amounts that are a whole number plus a quarter, half or three quarters. Null otherwise. */
    fun fraction(q: Double): String? {
        val nearest = Math.round(q * 4) / 4.0
        if (Math.abs(q - nearest) > 0.02 || nearest <= 0) return null
        val whole = Math.floor(nearest).toInt()
        val symbol = when (nearest - whole) {
            0.25 -> "¼"
            0.5 -> "½"
            0.75 -> "¾"
            else -> return null
        }
        return if (whole == 0) symbol else "$whole$symbol"
    }

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

    /** Default "uses per tap": half a kilo or litre, 100 g or ml, a quarter of a % item, otherwise one. */
    fun defaultStep(unit: QtyUnit): Double = when (unit) {
        QtyUnit.KG, QtyUnit.LITRE -> 0.5
        QtyUnit.GRAM, QtyUnit.ML -> 100.0
        QtyUnit.LEVEL -> 0.25
        else -> 1.0
    }

    /** Default "running low at": 25% for % items, 200 g or ml, half a kilo or litre, otherwise one. */
    fun defaultLowAt(unit: QtyUnit): Double = when (unit) {
        QtyUnit.LEVEL -> 0.25
        QtyUnit.GRAM, QtyUnit.ML -> 200.0
        QtyUnit.KG, QtyUnit.LITRE -> 0.5
        else -> 1.0
    }

    /** Default "running low at" when counting in meals. */
    const val DEFAULT_LOW_MEALS = 2.0

    /** What one tap of − or Shared takes: the item's step, never more than what's left. */
    fun useAmount(step: Double, stock: Double): Double = Qty.round(minOf(step.coerceAtLeast(0.001), stock)).coerceAtLeast(0.0)

    /**
     * What one tap of ＋ adds: a whole pack if the item comes in packs (10 eggs); for kilos and
     * litres, the size you usually buy (the last purchase), so a 5 kg bag of rice adds 5 kg;
     * otherwise one.
     */
    fun buyAmount(unit: QtyUnit, entries: List<Entry>, packSize: Double = 0.0): Double = when {
        packSize > 0 -> packSize
        unit.byWeight -> lastBought(entries)?.quantity?.takeIf { it > 0 } ?: if (unit.priceDivisor > 1) 500.0 else 1.0
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

    /** Price per unit (per kg, per packet...) of a purchase, rounded to the cent. Grams and ml give per kg and per litre. */
    fun unitPriceMinor(e: Entry, unit: QtyUnit = QtyUnit.PIECE): Long? {
        val price = e.priceMinor ?: return null
        if (e.quantity <= 0) return null
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(unit.priceDivisor.toLong()))
            .divide(BigDecimal.valueOf(e.quantity), 0, RoundingMode.HALF_UP).toLong()
    }

    /** Total for a quantity at a unit price: 1.5 kg at €1.20/kg = €1.80; 500 g at €2/kg = €1. */
    fun totalMinor(unitPriceMinor: Long, quantity: Double, unit: QtyUnit = QtyUnit.PIECE): Long =
        BigDecimal.valueOf(unitPriceMinor).multiply(BigDecimal.valueOf(quantity))
            .divide(BigDecimal.valueOf(unit.priceDivisor.toLong()), 0, RoundingMode.HALF_UP).toLong()

    /** The shop it was last bought at, or "" if never or not noted. */
    fun lastShop(entries: List<Entry>): String =
        entries.filter { it.type == EventType.BOUGHT && it.shop.isNotBlank() }.maxByOrNull { it.date }?.shop?.trim() ?: ""

    data class Cheaper(val shop: String, val unitPriceMinor: Long)

    /**
     * The cheapest other shop, by its latest unit price in the last [days] days, if it was cheaper
     * than [unitPriceMinor] at [shop]. Null if nowhere else was cheaper.
     */
    fun cheaperElsewhere(entries: List<Entry>, unit: QtyUnit, unitPriceMinor: Long, shop: String, today: LocalDate, days: Long = 180): Cheaper? {
        val from = today.minusDays(days)
        return entries
            .filter { it.type == EventType.BOUGHT && it.shop.isNotBlank() && !it.shop.trim().equals(shop.trim(), ignoreCase = true) && !it.date.isBefore(from) }
            .groupBy { it.shop.trim().lowercase() }
            .mapNotNull { (_, list) ->
                val latest = list.filter { it.priceMinor != null }.maxByOrNull { it.date } ?: return@mapNotNull null
                unitPriceMinor(latest, unit)?.let { Cheaper(latest.shop.trim(), it) }
            }
            .filter { it.unitPriceMinor < unitPriceMinor }
            .minByOrNull { it.unitPriceMinor }
    }

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
    fun status(entries: List<Entry>, lowAt: Double, today: LocalDate): StockStatus =
        status(entries, Counting(lowAt = lowAt), today)

    /**
     * Out at zero. When counting meals: red (LAST) with one meal or less left. Low at or below
     * "running low at", in meals or in the item's unit. Items counted in pieces, packets or kilos
     * are also low when they'll likely run out within 2 days; % and meal items only by their own
     * threshold, so they don't nag early. A "running low at" of 0 turns the guessing off too.
     */
    fun status(entries: List<Entry>, counting: Counting, today: LocalDate): StockStatus {
        val stock = stock(entries)
        if (stock <= 0) return StockStatus.OUT
        if (counting.countsMeals) {
            val meals = Meals.left(stock, counting.mealsPerUnit)
            if (meals <= 1.0) return StockStatus.LAST
            if (counting.lowInMeals && meals <= counting.lowAt) return StockStatus.LOW
        }
        if (!(counting.lowInMeals && counting.countsMeals) && stock <= counting.lowAt) return StockStatus.LOW
        if (counting.unit.isLevel || counting.countsMeals) return StockStatus.OK
        // "Remind me at 0" means only when it's gone: no guessing from how fast you use it.
        if (counting.lowAt <= 0) return StockStatus.OK
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

    /**
     * First day of the week [day] is in. After "Start new week", weeks run seven days from [anchor]
     * (the day you pressed it); before that, from the chosen [first] day of the week.
     */
    fun weekStart(day: LocalDate, first: DayOfWeek = DayOfWeek.MONDAY, anchor: LocalDate? = null): LocalDate {
        if (anchor != null && !day.isBefore(anchor)) {
            val weeks = ChronoUnit.DAYS.between(anchor, day) / 7
            return anchor.plusWeeks(weeks)
        }
        return day.with(TemporalAdjusters.previousOrSame(first))
    }

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
        /** Long-lasting things bought this week (not in the numbers above). */
        val longItems: List<ItemWeek> = emptyList(),
        val longSpentMinor: Long = 0,
        /** Spent on cravings: bought for fun, an avoidable expense. */
        val cravingMinor: Long = 0,
    )

    /**
     * Seven-day summary starting on [start] (Monday unless you chose another day). [entriesByItem] maps item id to its entries; [categoryOf] gives an
     * item's category. Used includes shared.
     */
    fun week(
        start: LocalDate,
        entriesByItem: Map<Long, List<Entry>>,
        isLong: (Long) -> Boolean = { false },
        categoryOf: (Long) -> String,
    ): Week {
        val end = start.plusDays(6)
        val all = entriesByItem.mapNotNull { (id, entries) ->
            val inWeek = entries.filter { !it.weekDate.isBefore(start) && !it.weekDate.isAfter(end) }
            if (inWeek.isEmpty()) return@mapNotNull null
            val used = inWeek.filter { it.type == EventType.USED || it.type == EventType.SHARED }.sumOf { it.quantity }
            val shared = inWeek.filter { it.type == EventType.SHARED }.sumOf { it.quantity }
            val boughtEntries = inWeek.filter { it.type == EventType.BOUGHT }
            val week = ItemWeek(id, Qty.round(used), Qty.round(shared), Qty.round(boughtEntries.sumOf { it.quantity }), boughtEntries.sumOf { it.priceMinor ?: 0 })
            if (week.used == 0.0 && week.bought == 0.0) null else week
        }
        // Long-lasting things (face cream, toilet paper) are counted in Budget, not in the weekly shop.
        val longItems = all.filter { isLong(it.itemId) }.sortedWith(compareByDescending<ItemWeek> { it.spentMinor }.thenByDescending { it.used })
        val items = all.filter { !isLong(it.itemId) }
        val timesShared = entriesByItem.values.sumOf { entries ->
            entries.count { it.type == EventType.SHARED && !it.weekDate.isBefore(start) && !it.weekDate.isAfter(end) }
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
            longItems = longItems,
            longSpentMinor = longItems.sumOf { it.spentMinor },
            cravingMinor = entriesByItem.values.sumOf { list ->
                list.filter { it.type == EventType.BOUGHT && it.craving && !it.weekDate.isBefore(start) && !it.weekDate.isAfter(end) }.sumOf { it.priceMinor ?: 0 }
            },
        )
    }

    data class LongBuy(val itemId: Long, val date: LocalDate, val quantity: Double, val spentMinor: Long)

    data class MonthSpend(
        val everydayMinor: Long,
        val longMinor: Long,
        val everydayByCategory: Map<String, Long>,
        val longByCategory: Map<String, Long>,
        val longBuys: List<LongBuy>,
    )

    /** What was spent in the month that starts on [first]: everyday shopping and long-lasting items apart. */
    fun month(
        first: LocalDate,
        entriesByItem: Map<Long, List<Entry>>,
        isLong: (Long) -> Boolean,
        categoryOf: (Long) -> String,
    ): MonthSpend {
        val last = first.withDayOfMonth(first.lengthOfMonth())
        var everyday = 0L
        var long = 0L
        val everydayBy = mutableMapOf<String, Long>()
        val longBy = mutableMapOf<String, Long>()
        val buys = mutableListOf<LongBuy>()
        entriesByItem.forEach { (id, entries) ->
            entries.filter { it.type == EventType.BOUGHT && !it.weekDate.isBefore(first) && !it.weekDate.isAfter(last) }.forEach { e ->
                val cost = e.priceMinor ?: 0L
                if (isLong(id)) {
                    long += cost
                    longBy[categoryOf(id)] = (longBy[categoryOf(id)] ?: 0L) + cost
                    buys += LongBuy(id, e.weekDate, e.quantity, cost)
                } else {
                    everyday += cost
                    everydayBy[categoryOf(id)] = (everydayBy[categoryOf(id)] ?: 0L) + cost
                }
            }
        }
        return MonthSpend(everyday, long, everydayBy.filterValues { it > 0 }, longBy.filterValues { it > 0 }, buys.sortedByDescending { it.date })
    }

    /** Total paid for everything bought from [from] to [to], both included. */
    fun spentBetween(entriesByItem: Map<Long, List<Entry>>, from: LocalDate, to: LocalDate): Long =
        entriesByItem.values.sumOf { entries ->
            entries.filter { it.type == EventType.BOUGHT && !it.date.isBefore(from) && !it.date.isAfter(to) }.sumOf { it.priceMinor ?: 0 }
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
