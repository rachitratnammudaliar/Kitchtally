package com.rachitratnammudaliar.kitchtally.backup

import com.rachitratnammudaliar.kitchtally.data.Event
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.data.ShoppingNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTest {
    @Test
    fun roundTripKeepsItemsAndHistory() {
        val rice = Item(id = 7, name = "Basmati rice", category = "Grains & flour", unit = "KG", life = "LONG", lowAt = 1.5, refillNeeded = true, photoFile = "x.jpg", notes = "5 kg bag", createdAt = 1, step = 0.25, hiddenFromList = true, packSize = 5.0, unsure = true, countMeals = true, mealsPerUnit = 6.0, lowInMeals = true)
        val bread = Item(id = 9, name = "Bread", category = "Bread & bakery", unit = "PACKET", life = "SHORT", createdAt = 2)
        val events = listOf(
            Event(id = 1, itemId = 7, type = "BOUGHT", epochDay = 20000, quantity = 5.0, priceMinor = 1245, shop = "Maxima", receiptFile = "r.jpg", createdAt = 3),
            Event(id = 2, itemId = 7, type = "USED", epochDay = 20001, quantity = 0.5, createdAt = 4),
            Event(id = 3, itemId = 9, type = "SHARED", epochDay = 20002, quantity = 1.0, createdAt = 5),
            Event(id = 4, itemId = 9, type = "ADJUST", epochDay = 20002, quantity = -1.0, note = "Correction", createdAt = 6),
        )
        val notes = listOf(ShoppingNote(id = 4, text = "Candles", createdAt = 9))
        val back = Backup.decode(Backup.encode(listOf(rice, bread), events, notes))
        // Old category names come back under their new names.
        assertEquals(listOf(rice.copy(photoFile = "", category = "Staples"), bread), back.items)
        assertEquals(events.map { it.copy(id = 0, receiptFile = "") }, back.events)
        assertEquals(listOf(ShoppingNote(text = "Candles", createdAt = 9)), back.notes)
    }

    @Test
    fun skipsBrokenEntries() {
        val json = """
            {"format":"kitchtally-backup","items":[
              {"ref":1,"name":"Milk","unit":"NOPE","life":"???","lowAt":-3},
              {"ref":2,"name":"  "},
              {"name":"No ref"}
            ],"events":[
              {"item":1,"type":"BOUGHT","epochDay":20000,"quantity":2,"priceMinor":199,"shop":"IKI"},
              {"item":1,"type":"USED","epochDay":20001,"quantity":-1},
              {"item":5,"type":"USED","epochDay":20001,"quantity":1},
              {"item":1,"type":"USED","quantity":1},
              {"item":1,"type":"USED","epochDay":20001,"quantity":1,"priceMinor":50}
            ]}
        """.trimIndent()
        val c = Backup.decode(json)
        assertEquals(1, c.items.size)
        assertEquals("PIECE", c.items[0].unit)
        assertEquals(1.0, c.items[0].step, 0.0)
        assertEquals("MEDIUM", c.items[0].life)
        assertEquals(1.0, c.items[0].lowAt, 0.0)
        assertEquals(2, c.events.size)
        assertNull(c.events[1].priceMinor)
    }

    @Test
    fun version1BackupsGetDefaultSteps() {
        val json = """{"format":"kitchtally-backup","version":1,"items":[{"ref":1,"name":"Rice","unit":"KG"},{"ref":2,"name":"Bread","unit":"PACKET"}]}"""
        val c = Backup.decode(json)
        assertEquals(0.5, c.items[0].step, 0.0)
        assertEquals(1.0, c.items[1].step, 0.0)
        assertEquals(false, c.items[0].hiddenFromList)
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("nope") }
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("""{"format":"duedial-backup"}""") }
    }
}
