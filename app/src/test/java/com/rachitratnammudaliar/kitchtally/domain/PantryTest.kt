package com.rachitratnammudaliar.kitchtally.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PantryTest {
    private val today = LocalDate.of(2026, 11, 4) // a Wednesday
    private fun d(daysAgo: Long) = today.minusDays(daysAgo)
    private fun bought(daysAgo: Long, qty: Double = 1.0, price: Long? = null, shop: String = "") = Entry(EventType.BOUGHT, d(daysAgo), qty, price, shop)
    private fun used(daysAgo: Long, qty: Double = 1.0) = Entry(EventType.USED, d(daysAgo), qty)
    private fun shared(daysAgo: Long, qty: Double = 1.0) = Entry(EventType.SHARED, d(daysAgo), qty)

    @Test
    fun stockAddsPurchasesAndTakesUses() {
        val e = listOf(Entry(EventType.ADJUST, d(10), 2.0), bought(5, 2.0), used(3), shared(1, 0.5))
        assertEquals(2.5, Pantry.stock(e), 0.0)
        assertEquals(0.0, Pantry.stock(emptyList()), 0.0)
        assertEquals(1.0, Pantry.stock(listOf(bought(1, 0.1), bought(1, 0.2), bought(1, 0.7))), 0.0)
    }

    @Test
    fun statusOutLowOk() {
        assertEquals(StockStatus.OUT, Pantry.status(listOf(bought(5), used(1)), 1.0, today))
        assertEquals(StockStatus.LOW, Pantry.status(listOf(bought(5, 2.0), used(1)), 1.0, today))
        assertEquals(StockStatus.OK, Pantry.status(listOf(bought(5, 3.0)), 1.0, today))
        assertEquals(StockStatus.OK, Pantry.status(listOf(bought(5, 1.0)), 0.0, today))
        // Remind me at 0: heavy use never says "running low" while some is left.
        val fast = listOf(bought(6, 4.0), used(5, 1.0), used(3, 1.0), used(1, 1.0))
        assertEquals(StockStatus.LOW, Pantry.status(fast, 0.5, today))
        assertEquals(StockStatus.OK, Pantry.status(fast, 0.0, today))
    }

    @Test
    fun dailyUseAndDaysLeft() {
        // 4 packets used over 8 days (first use 7 days ago, counting today) = 0.5 a day
        val e = listOf(bought(10, 6.0), used(7), used(5), used(3), shared(1))
        assertEquals(0.5, Pantry.dailyUse(e, today)!!, 0.0001)
        assertEquals(4L, Pantry.daysLeft(e, today))
        // Fast use makes it LOW even above the threshold
        val fast = listOf(bought(4, 3.0), used(3), used(2), used(1, 0.0).copy(quantity = 1.0))
        assertEquals(0.0, Pantry.stock(fast), 0.0)
        assertNull(Pantry.dailyUse(listOf(bought(3), used(1)), today))
    }

    @Test
    fun freshnessOnlyForShortAndMedium() {
        assertTrue(Pantry.checkFreshness(listOf(bought(6)), Life.SHORT, today))
        assertFalse(Pantry.checkFreshness(listOf(bought(3)), Life.SHORT, today))
        assertTrue(Pantry.checkFreshness(listOf(bought(11)), Life.MEDIUM, today))
        assertFalse(Pantry.checkFreshness(listOf(bought(10)), Life.MEDIUM, today))
        assertFalse(Pantry.checkFreshness(listOf(bought(60)), Life.LONG, today))
        assertFalse(Pantry.checkFreshness(listOf(bought(6), used(1)), Life.SHORT, today))
    }

    @Test
    fun pricesPerUnit() {
        val rice = bought(1, 5.0, 1245, "Maxima")
        assertEquals(249L, Pantry.unitPriceMinor(rice))
        assertEquals(180L, Pantry.totalMinor(120, 1.5))
        assertNull(Pantry.unitPriceMinor(bought(1)))
        val e = listOf(bought(10, 1.0, 100, "IKI"), bought(5, 2.0, null, "Lidl"), bought(2, 2.0, 300, "Norfa"))
        assertEquals("Norfa", Pantry.lastPriced(e)?.shop)
        assertEquals(d(2), Pantry.lastBought(e)?.date)
    }

    @Test
    fun weekSummaryCountsSharedInsideUsed() {
        val start = Pantry.weekStart(today)
        assertEquals(LocalDate.of(2026, 11, 2), start)
        val bread = listOf(bought(9, 2.0, 178), bought(2, 2.0, 178, "IKI"), used(2), used(1), shared(0))
        val rice = listOf(bought(1, 5.0, 1245), used(1, 0.5))
        val week = Pantry.week(start, mapOf(1L to bread, 2L to rice)) { if (it == 1L) "Bread & bakery" else "Grains & flour" }
        val b = week.items.first { it.itemId == 1L }
        assertEquals(3.0, b.used, 0.0)
        assertEquals(1.0, b.shared, 0.0)
        assertEquals(2.0, b.bought, 0.0)
        assertEquals(178L, b.spentMinor)
        assertEquals(1423L, week.spentMinor)
        assertEquals(1245L, week.spentByCategory["Grains & flour"])
        assertEquals(3.5, week.usedTotal, 0.0)
        assertEquals(1.0, week.sharedTotal, 0.0)
        assertEquals(1L, week.items.first().itemId)
        assertEquals(2, week.itemsUsed)
        assertEquals(1, week.timesShared)
        assertEquals(2, week.itemsBought)
    }

    @Test
    fun aShopForNextWeekCountsInNextWeek() {
        val start = Pantry.weekStart(today)
        val next = start.plusWeeks(1)
        // Bought on the last day of this week, but meant for next week.
        val forNext = Entry(EventType.BOUGHT, start.plusDays(6), 2.0, 300, "IKI", next)
        val usedNow = Entry(EventType.USED, start.plusDays(6), 1.0)
        val items = mapOf(1L to listOf(forNext, usedNow))
        val thisWeek = Pantry.week(start, items) { "Other" }
        val nextWeek = Pantry.week(next, items) { "Other" }
        assertEquals(0, thisWeek.itemsBought)
        assertEquals(0L, thisWeek.spentMinor)
        assertEquals(1, thisWeek.itemsUsed)
        assertEquals(1, nextWeek.itemsBought)
        assertEquals(300L, nextWeek.spentMinor)
        // Stock is unaffected: it is in the cupboard now.
        assertEquals(1.0, Pantry.stock(listOf(forNext, usedNow)), 0.0)
    }

    @Test
    fun longLastingThingsAreKeptOutOfTheWeeklyShop() {
        val start = Pantry.weekStart(today)
        val cream = listOf(Entry(EventType.BOUGHT, start.plusDays(1), 1.0, 1200, "Maxima"))
        val milk = listOf(Entry(EventType.BOUGHT, start.plusDays(1), 1.0, 85, "IKI"), Entry(EventType.USED, start.plusDays(2), 0.5))
        val items = mapOf(1L to cream, 2L to milk)
        val week = Pantry.week(start, items, isLong = { it == 1L }, categoryOf = { "Other" })
        assertEquals(85L, week.spentMinor)
        assertEquals(1, week.itemsBought)
        assertEquals(1, week.longItems.size)
        assertEquals(1200L, week.longSpentMinor)
        val m = Pantry.month(start.withDayOfMonth(1), items, isLong = { it == 1L }, categoryOf = { if (it == 1L) "Personal care" else "Dairy & eggs" })
        assertEquals(85L, m.everydayMinor)
        assertEquals(1200L, m.longMinor)
        assertEquals(1200L, m.longByCategory["Personal care"])
        assertEquals(1, m.longBuys.size)
        assertTrue(Categories.isLongLasting("Personal care"))
        assertTrue(Categories.isLongLasting("household"))
        assertFalse(Categories.isLongLasting("Staples"))
    }

    @Test
    fun weekCanStartOnAnotherDay() {
        assertEquals(LocalDate.of(2026, 11, 1), Pantry.weekStart(today, java.time.DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 11, 2), Pantry.weekStart(today))
        assertEquals(LocalDate.of(2026, 11, 7), Pantry.weekStart(LocalDate.of(2026, 11, 8), java.time.DayOfWeek.SATURDAY))
    }

    @Test
    fun tapAmounts() {
        assertEquals(1.0, Pantry.defaultStep(QtyUnit.PACKET), 0.0)
        assertEquals(0.5, Pantry.defaultStep(QtyUnit.KG), 0.0)
        // − never takes more than what's left
        assertEquals(0.5, Pantry.useAmount(0.5, 4.5), 0.0)
        assertEquals(0.3, Pantry.useAmount(0.5, 0.3), 0.0)
        assertEquals(0.0, Pantry.useAmount(1.0, 0.0), 0.0)
        assertEquals(0.0, Pantry.useAmount(1.0, -2.0), 0.0)
        // ＋ adds one packet, but a whole bag of rice
        val rice = listOf(bought(10, 5.0, 1245, "Maxima"), used(2, 0.5))
        assertEquals(5.0, Pantry.buyAmount(QtyUnit.KG, rice), 0.0)
        assertEquals(1245L, Pantry.expectedPriceMinor(QtyUnit.KG, rice))
        val bread = listOf(bought(3, 2.0, 178, "IKI"))
        assertEquals(1.0, Pantry.buyAmount(QtyUnit.PACKET, bread), 0.0)
        assertEquals(89L, Pantry.expectedPriceMinor(QtyUnit.PACKET, bread))
        assertEquals(1.0, Pantry.buyAmount(QtyUnit.KG, emptyList()), 0.0)
        assertNull(Pantry.expectedPriceMinor(QtyUnit.KG, listOf(bought(1, 2.0))))
    }

    @Test
    fun packs() {
        // Eggs: counted in pieces, bought in packs of 10 for €2.89
        val eggs = listOf(bought(5, 10.0, 289, "IKI"), used(3, 2.0))
        assertEquals(10.0, Pantry.buyAmount(QtyUnit.PIECE, eggs, 10.0), 0.0)
        assertEquals(289L, Pantry.expectedPriceMinor(QtyUnit.PIECE, eggs, 10.0))
        assertEquals(58L, Pantry.priceFor(eggs, 2.0))
        assertEquals("1 pack = 10 pieces", Pantry.packLabel(QtyUnit.PIECE, 10.0))
        assertEquals("", Pantry.packLabel(QtyUnit.PIECE, 0.0))
        assertEquals("about 0.8 packs", Pantry.packsHint(8.0, 10.0))
        assertEquals("about 1 pack", Pantry.packsHint(10.0, 10.0))
        assertEquals("about 3 packs", Pantry.packsHint(25.0, 10.0))
        assertEquals("", Pantry.packsHint(0.0, 10.0))
    }

    @Test
    fun quantitiesAndMoney() {
        assertEquals("1.5", Qty.format(1.5))
        assertEquals("2", Qty.format(2.0))
        assertEquals("3", Qty.approx(2.917))
        assertEquals("0.5", Qty.approx(0.46))
        assertEquals(0.5, Qty.parse("0,5")!!, 0.0)
        assertNull(Qty.parse("0"))
        assertNull(Qty.parse("abc"))
        assertEquals(120L, Money.parseToMinor("1,2"))
        assertEquals(0L, Money.parseToMinor("0"))
        assertNull(Money.parseToMinor("1.234"))
        assertNull(Money.parseToMinor("-1"))
        assertEquals("1.2", Money.minorToInput(120))
        assertEquals("2 packets", QtyUnit.PACKET.label(2.0))
        assertEquals("1 packet", QtyUnit.PACKET.label(1.0))
        assertEquals("0.5 kg", QtyUnit.KG.label(0.5))
    }

    @Test
    fun namesSuggestCategories() {
        val expected = mapOf(
            "Eggs" to "Dairy & eggs", "Veggie mix" to "Vegetables", "Pasta/spagheti" to "Staples", "Chicken" to "Meat & protein",
            "Bread" to "Bread & bakery", "Milk /Substitute" to "Dairy & eggs", "Red Onion" to "Vegetables", "Potato" to "Vegetables",
            "Carrot" to "Vegetables", "Chole" to "Staples", "Dishwashing liquid" to "Household", "Tomato Puree" to "Sauces & spreads",
            "Mushroom" to "Vegetables", "Garlic Sauce" to "Sauces & spreads", "Clever Yogurt" to "Dairy & eggs", "Cheese" to "Dairy & eggs",
            "Rice" to "Staples", "Oil" to "Staples", "Butter" to "Dairy & eggs", "Salt" to "Staples", "Sugar" to "Staples",
            "Spices" to "Spices", "Atta" to "Staples", "Body wash" to "Personal care", "Toilet paper" to "Household",
            "Toothpaste" to "Personal care", "Face wash" to "Personal care", "Body cream" to "Personal care", "Face cream" to "Personal care",
            "Peanut butter" to "Sauces & spreads", "Eggplant" to "Vegetables", "Chickpeas" to "Staples", "Hair oil" to "Personal care",
            "Ice cream" to "Snacks", "Apples" to "Fruit",
        )
        expected.forEach { (name, category) -> assertEquals(name, category, Suggestions.forName(name)?.category) }
        val eggs = Suggestions.forName("Eggs")!!
        assertEquals(QtyUnit.PIECE, eggs.unit)
        assertEquals(10.0, eggs.packSize, 0.0)
        assertEquals(Life.SHORT, Suggestions.forName("Bread")!!.life)
        assertEquals(QtyUnit.KG, Suggestions.forName("Rice")!!.unit)
        assertNull(Suggestions.forName("Birthday candles"))
        assertNull(Suggestions.forName(" "))
        assertEquals("🥬", Categories.emojiFor("Vegetables"))
        assertEquals("Staples", Categories.renamed["Grains & flour"])
    }

    @Test
    fun shopOptionsKeepPresetsFirstWithoutDuplicates() {
        assertEquals(listOf("IKI", "Norfa", "Maxima", "Lidl", "Rimi", "Pepco", "Aibė"), Shops.options(listOf("iki", "Aibė", " ", "Aibė")))
    }

    @Test
    fun percentItems() {
        val u = QtyUnit.LEVEL
        assertEquals("80%", u.label(0.8))
        assertEquals("25%", u.label(0.25))
        assertEquals("1 full", u.label(1.0))
        assertEquals("1 full + 50%", u.label(1.5))
        assertEquals("80% left", u.leftLabel(0.8))
        assertEquals("Full", u.leftLabel(1.0))
        assertEquals("2 full", u.leftLabel(2.0))
        assertEquals("1 full + 25%", u.leftLabel(1.25))
        assertEquals(0.25, Pantry.defaultStep(u), 0.0)
        assertEquals(0.25, Pantry.defaultLowAt(u), 0.0)
        assertEquals(1.0, Pantry.buyAmount(u, listOf(bought(3))), 0.0)
        // Low only at its own threshold: 25% and below, never earlier because of fast use.
        val bread = Counting(u, 0.25)
        assertEquals(StockStatus.OK, Pantry.status(listOf(bought(2), used(1, 0.25), used(0, 0.25)), bread, today))
        assertEquals(StockStatus.LOW, Pantry.status(listOf(bought(2), used(1, 0.5), used(0, 0.25)), bread, today))
        assertEquals(StockStatus.OUT, Pantry.status(listOf(bought(2), used(0, 1.0)), bread, today))
        // 10% for oil.
        assertEquals(StockStatus.OK, Pantry.status(listOf(bought(9), used(1, 0.85)), Counting(u, 0.1), today))
        assertEquals(StockStatus.LOW, Pantry.status(listOf(bought(9), used(1, 0.9)), Counting(u, 0.1), today))
    }

    @Test
    fun gramsArePricedPerKg() {
        val g = QtyUnit.GRAM
        assertEquals("500 g", g.label(500.0))
        val e = bought(1, 500.0, 120)
        assertEquals(240L, Pantry.unitPriceMinor(e, g))
        assertEquals(100L, Pantry.totalMinor(200, 500.0, g))
        assertEquals(500.0, Pantry.buyAmount(g, emptyList()), 0.0)
        assertEquals(750.0, Pantry.buyAmount(g, listOf(bought(2, 750.0))), 0.0)
        assertEquals(100.0, Pantry.defaultStep(g), 0.0)
        assertEquals(1500L, Pantry.unitPriceMinor(bought(1, 200.0, 300), QtyUnit.ML))
        assertEquals(240L, Pantry.unitPriceMinor(bought(1, 0.5, 120), QtyUnit.KG))
    }

    @Test
    fun mealsLeftAndRed() {
        assertEquals("about 8 meals", Meals.label(Meals.left(5.0, 1.6)))
        assertEquals("1 meal", Meals.label(1.0))
        assertEquals("1 to 2 meals", Meals.label(1.5))
        assertEquals("less than 1 meal", Meals.label(0.4))
        assertEquals("no meals", Meals.label(0.0))
        assertEquals(1.6, Meals.perUnit(5.0, 8.0)!!, 0.0)
        assertNull(Meals.perUnit(0.0, 8.0))
        assertEquals(0.25, Meals.cookAmount(1.0, 4.0), 0.0)
        assertEquals(0.5, Meals.cookAmount(0.5, 0.0), 0.0)

        val potatoes = Counting(QtyUnit.PIECE, lowAt = 2.0, mealsPerUnit = 0.6, lowInMeals = true)
        // 5 potatoes = 3 meals: fine. 3 = about 1.8: low. 1 = 0.6: red.
        assertEquals(StockStatus.OK, Pantry.status(listOf(bought(3, 5.0)), potatoes, today))
        assertEquals(StockStatus.LOW, Pantry.status(listOf(bought(3, 5.0), used(1, 2.0)), potatoes, today))
        assertEquals(StockStatus.LAST, Pantry.status(listOf(bought(3, 5.0), used(1, 4.0)), potatoes, today))
        // Counting meals, but "running low at" in pieces: red still comes at one meal.
        val spaghetti = Counting(QtyUnit.PACKET, lowAt = 0.0, mealsPerUnit = 4.0)
        assertEquals(StockStatus.OK, Pantry.status(listOf(bought(3, 1.0), used(1, 0.5)), spaghetti, today))
        assertEquals(StockStatus.LAST, Pantry.status(listOf(bought(3, 1.0), used(1, 0.75)), spaghetti, today))
    }

    @Test
    fun cheaperElsewhereAndLastShop() {
        val entries = listOf(bought(30, 1.0, 199, "IKI"), bought(10, 1.0, 159, "Lidl"), bought(5, 1.0, 189, "Maxima"), bought(400, 1.0, 99, "Rimi"))
        assertEquals("Maxima", Pantry.lastShop(entries))
        val cheaper = Pantry.cheaperElsewhere(entries, QtyUnit.PIECE, 199, "IKI", today)!!
        assertEquals("Lidl", cheaper.shop)
        assertEquals(159L, cheaper.unitPriceMinor)
        assertNull(Pantry.cheaperElsewhere(entries, QtyUnit.PIECE, 150, "IKI", today))
        assertNull(Pantry.cheaperElsewhere(entries, QtyUnit.PIECE, 159, "Lidl", today)?.takeIf { it.shop == "Lidl" })
        assertEquals("", Pantry.lastShop(listOf(bought(1))))
    }

    @Test
    fun spendingBetweenDates() {
        val byItem = mapOf(1L to listOf(bought(2, 1.0, 100), bought(40, 1.0, 999)), 2L to listOf(bought(0, 1.0, 250), used(0)))
        assertEquals(350L, Pantry.spentBetween(byItem, d(10), today))
    }

    @Test
    fun suggestionsCountMealsAndPieces() {
        assertEquals(QtyUnit.PIECE, Suggestions.forName("Red Onion")!!.unit)
        assertEquals(QtyUnit.PIECE, Suggestions.forName("Potato")!!.unit)
        assertTrue(Suggestions.forName("Potato")!!.countMeals)
        assertTrue(Suggestions.forName("Pasta/spagheti")!!.countMeals)
        assertFalse(Suggestions.forName("Salt")!!.countMeals)
        assertFalse(Suggestions.forName("Oil")!!.countMeals)
        assertEquals(QtyUnit.LEVEL, Suggestions.forName("Oil")!!.unit)
        assertEquals(QtyUnit.LEVEL, Suggestions.forName("Bread")!!.unit)
        assertEquals(QtyUnit.LEVEL, Suggestions.forName("Tomato ketchup")!!.unit)
        assertEquals(QtyUnit.LEVEL, Suggestions.forName("Veggie mix")!!.unit)
        assertEquals("Spices", Suggestions.forName("Chilli powder")!!.category)
        assertEquals(QtyUnit.PIECE, Suggestions.forName("Toothbrush")!!.unit)
        assertEquals(QtyUnit.LEVEL, Suggestions.forName("Toothpaste")!!.unit)
    }
}
