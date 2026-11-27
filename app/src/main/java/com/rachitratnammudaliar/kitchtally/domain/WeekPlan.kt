package com.rachitratnammudaliar.kitchtally.domain

/** The week agenda: which pantry items you plan to use on which day (day = epoch day). Kept on the phone only. */
object WeekPlan {
    fun encode(plan: Map<Long, List<Long>>): String =
        plan.entries.filter { it.value.isNotEmpty() }.sortedBy { it.key }
            .joinToString(";") { "${it.key}:${it.value.distinct().joinToString(",")}" }

    fun decode(text: String): Map<Long, List<Long>> =
        text.split(';').mapNotNull { part ->
            val bits = part.split(':', limit = 2)
            if (bits.size < 2) return@mapNotNull null
            val day = bits[0].toLongOrNull() ?: return@mapNotNull null
            val ids = bits[1].split(',').mapNotNull { it.toLongOrNull() }.distinct()
            if (ids.isEmpty()) null else day to ids
        }.toMap()

    /** Adds the item to the day, or takes it off if it's already there. */
    fun toggle(plan: Map<Long, List<Long>>, day: Long, itemId: Long): Map<Long, List<Long>> {
        val now = plan[day].orEmpty()
        val next = if (itemId in now) now - itemId else now + itemId
        return if (next.isEmpty()) plan - day else plan + (day to next)
    }

    /** Only today and later. */
    fun upcoming(plan: Map<Long, List<Long>>, today: Long): Map<Long, List<Long>> = plan.filterKeys { it >= today }

    /** How many days the item is planned for. */
    fun times(plan: Map<Long, List<Long>>, itemId: Long): Int = plan.values.count { itemId in it }

    /** How much more you need than you have: planned days × one use each, minus what's in stock. */
    fun shortBy(plan: Map<Long, List<Long>>, itemId: Long, step: Double, stock: Double): Double =
        Qty.round((times(plan, itemId) * step.coerceAtLeast(0.001) - stock).coerceAtLeast(0.0))
}

/** Weekly budget maths. Long-lasting things are never in the weekly bill, so they never count here. */
object WeeklyBudget {
    /** A weekly budget as a monthly figure: €15 a week is about €65 a month. */
    fun monthlyOf(weeklyMinor: Long): Long = Math.round(weeklyMinor * 52.0 / 12.0)

    /** What a shortfall should cost at the last price paid per unit; 0 when there is no price yet. */
    fun estimateMinor(unitPriceMinor: Double?, shortBy: Double): Long =
        if (unitPriceMinor == null || shortBy <= 0) 0L else Math.round(unitPriceMinor * shortBy)
}

/** A dish you cook (name + the items it needs) and a dish planned for this week. */
data class Dish(
    val id: Long,
    val name: String,
    /** Everything the dish uses. */
    val ingredients: List<Long>,
    /** The ones that are nice to have: never counted as missing, never put on the shopping list. */
    val optional: Set<Long> = emptySet(),
    /** File name of the dish photo in the app's private storage, or empty. */
    val photo: String = "",
    /** How many meals one cooking gives (1-20): cook once, eat this many times. */
    val meals: Int = 1,
) {
    val essentials: List<Long> get() = ingredients.filter { it !in optional }
}

/** day = epoch day it's planned for, or 0 for "any day this week". until = last day it still counts. */
data class PlannedMeal(val dishId: Long, val day: Long, val until: Long)

object MealPlan {
    private fun clean(s: String) = s.replace(Regex("[\\t\\n\\r;:]"), " ").trim()

    fun encodeDishes(dishes: List<Dish>): String =
        dishes.joinToString("\n") { d ->
            val ing = d.ingredients.distinct().joinToString(",") { if (it in d.optional) "$it*" else "$it" }
            "${d.id}\t${clean(d.name)}\t$ing\t${clean(d.photo)}\t${d.meals.coerceIn(1, 20)}"
        }

    fun decodeDishes(text: String): List<Dish> = text.split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 3) return@mapNotNull null
        val id = p[0].toLongOrNull() ?: return@mapNotNull null
        val name = p[1].trim()
        if (name.isBlank()) return@mapNotNull null
        val tokens = p[2].split(',').filter { it.isNotBlank() }
        val ids = tokens.mapNotNull { it.trimEnd('*').toLongOrNull() }.distinct()
        val optional = tokens.filter { it.endsWith("*") }.mapNotNull { it.trimEnd('*').toLongOrNull() }.toSet()
        Dish(id, name, ids, optional, p.getOrNull(3)?.trim().orEmpty(), (p.getOrNull(4)?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 20))
    }

    fun encodePlan(plan: List<PlannedMeal>): String = plan.joinToString(";") { "${it.dishId}:${it.day}:${it.until}" }

    fun decodePlan(text: String): List<PlannedMeal> = text.split(';').mapNotNull { part ->
        val p = part.split(':')
        if (p.size < 3) return@mapNotNull null
        val dish = p[0].toLongOrNull() ?: return@mapNotNull null
        val day = p[1].toLongOrNull() ?: return@mapNotNull null
        val until = p[2].toLongOrNull() ?: return@mapNotNull null
        PlannedMeal(dish, day, until)
    }

    fun upcoming(plan: List<PlannedMeal>, today: Long): List<PlannedMeal> = plan.filter { it.until >= today }

    /** Meals you get from everything planned: each cooking gives the dish's number of meals. */
    fun mealsCovered(planned: List<PlannedMeal>, dishes: List<Dish>): Int =
        planned.sumOf { m -> dishes.firstOrNull { it.id == m.dishId }?.meals ?: 0 }

    fun nextId(dishes: List<Dish>): Long = (dishes.maxOfOrNull { it.id } ?: 0L) + 1

    /** Ingredients that are out or running low. */
    fun missing(dish: Dish, isOk: (Long) -> Boolean): List<Long> = dish.essentials.filter { !isOk(it) }

    /** Dishes planned in the last two weeks that are over now, newest first: what "same as last week" offers. */
    fun lastWeek(plan: List<PlannedMeal>, today: Long): List<Long> =
        plan.filter { it.until < today && it.until >= today - 13 }.sortedByDescending { it.until }.map { it.dishId }.distinct()

    /** Everything to buy across the planned dishes; an item used in two dishes counts once. */
    fun shopping(dishes: List<Dish>, isOk: (Long) -> Boolean): List<Long> = dishes.flatMap { missing(it, isOk) }.distinct()
}
