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
        assertFalse(Pantry.checkFreshness(listOf(bought(4)), Life.SHORT, today))
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
    fun shopOptionsKeepPresetsFirstWithoutDuplicates() {
        assertEquals(listOf("IKI", "Norfa", "Maxima", "Lidl", "Rimi", "Pepco", "Aibė"), Shops.options(listOf("iki", "Aibė", " ", "Aibė")))
    }
}
