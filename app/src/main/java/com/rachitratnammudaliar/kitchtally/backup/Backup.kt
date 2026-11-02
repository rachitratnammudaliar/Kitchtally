package com.rachitratnammudaliar.kitchtally.backup

import com.rachitratnammudaliar.kitchtally.data.Event
import com.rachitratnammudaliar.kitchtally.data.Item
import com.rachitratnammudaliar.kitchtally.domain.EventType
import com.rachitratnammudaliar.kitchtally.domain.Life
import com.rachitratnammudaliar.kitchtally.domain.QtyUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain JSON backups, readable by the Android and iOS apps. Photos stay on the phone.
 * Items are numbered within the file and events refer to those numbers.
 */
object Backup {
    private const val FORMAT = "kitchtally-backup"
    private const val VERSION = 1

    data class Contents(val items: List<Item>, val events: List<Event>)

    fun encode(items: List<Item>, events: List<Event>): String {
        val itemsJson = JSONArray()
        items.forEach { i ->
            itemsJson.put(
                JSONObject()
                    .put("ref", i.id)
                    .put("name", i.name)
                    .put("category", i.category)
                    .put("unit", i.unit)
                    .put("life", i.life)
                    .put("lowAt", i.lowAt)
                    .put("refillNeeded", i.refillNeeded)
                    .put("notes", i.notes)
                    .put("createdAt", i.createdAt),
            )
        }
        val ids = items.map { it.id }.toSet()
        val eventsJson = JSONArray()
        events.filter { it.itemId in ids }.forEach { e ->
            eventsJson.put(
                JSONObject()
                    .put("item", e.itemId)
                    .put("type", e.type)
                    .put("epochDay", e.epochDay)
                    .put("quantity", e.quantity)
                    .put("priceMinor", e.priceMinor ?: JSONObject.NULL)
                    .put("shop", e.shop)
                    .put("note", e.note)
                    .put("createdAt", e.createdAt),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("items", itemsJson)
            .put("events", eventsJson)
            .toString(2)
    }

    /**
     * Throws IllegalArgumentException if the file isn't a Kitchtally backup. Broken entries are skipped.
     * Returned items keep their file numbers as ids, and events point at those; the caller assigns real ids.
     */
    fun decode(text: String): Contents {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This file isn't a Kitchtally backup.")
        }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("This file isn't a Kitchtally backup.")

        val items = mutableListOf<Item>()
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        for (i in 0 until itemsJson.length()) {
            val o = itemsJson.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty() || !o.has("ref")) continue
            val lowAt = o.optDouble("lowAt", 1.0).let { if (it.isNaN() || it < 0) 1.0 else it.coerceAtMost(10_000.0) }
            items += Item(
                id = o.optLong("ref"),
                name = name.take(60),
                category = o.optString("category", "Groceries").trim().ifBlank { "Groceries" }.take(40),
                unit = QtyUnit.fromName(o.optString("unit")).name,
                life = Life.fromName(o.optString("life")).name,
                lowAt = lowAt,
                refillNeeded = o.optBoolean("refillNeeded", false),
                notes = o.optString("notes").take(500),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        val refs = items.map { it.id }.toSet()
        val events = mutableListOf<Event>()
        val eventsJson = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until eventsJson.length()) {
            val o = eventsJson.optJSONObject(i) ?: continue
            val ref = o.optLong("item", -1)
            val quantity = o.optDouble("quantity", Double.NaN)
            if (ref !in refs || !o.has("epochDay") || quantity.isNaN() || quantity == 0.0) continue
            val type = EventType.fromName(o.optString("type"))
            if (type != EventType.ADJUST && quantity < 0) continue
            val price = if (o.isNull("priceMinor")) null else o.optLong("priceMinor", -1).takeIf { it >= 0 }
            events += Event(
                itemId = ref,
                type = type.name,
                epochDay = o.getLong("epochDay"),
                quantity = quantity,
                priceMinor = if (type == EventType.BOUGHT) price else null,
                shop = o.optString("shop").trim().take(40),
                note = o.optString("note").take(200),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        return Contents(items, events)
    }
}
