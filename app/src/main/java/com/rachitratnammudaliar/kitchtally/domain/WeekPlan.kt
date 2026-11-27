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
