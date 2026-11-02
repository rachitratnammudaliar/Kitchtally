import Foundation

// MARK: - Units, life, events

enum QtyUnit: String, CaseIterable, Identifiable {
    case PIECE, PACKET, BOTTLE, BOX, BAG, KG, LITRE
    var id: String { rawValue }

    var singular: String {
        switch self {
        case .PIECE: return "piece"
        case .PACKET: return "packet"
        case .BOTTLE: return "bottle"
        case .BOX: return "box"
        case .BAG: return "bag"
        case .KG: return "kg"
        case .LITRE: return "litre"
        }
    }

    var plural: String {
        switch self {
        case .PIECE: return "pieces"
        case .PACKET: return "packets"
        case .BOTTLE: return "bottles"
        case .BOX: return "boxes"
        case .BAG: return "bags"
        case .KG: return "kg"
        case .LITRE: return "litres"
        }
    }

    var priceLabel: String {
        switch self {
        case .PIECE: return "each"
        case .KG: return "per kg"
        case .LITRE: return "per litre"
        default: return "per \(singular)"
        }
    }

    func label(_ qty: Double) -> String { "\(Qty.format(qty)) \(qty == 1 ? singular : plural)" }

    static func from(_ name: String) -> QtyUnit { QtyUnit(rawValue: name) ?? .PIECE }
}

enum Life: String, CaseIterable, Identifiable {
    case SHORT, MEDIUM, LONG
    var id: String { rawValue }

    var label: String {
        switch self {
        case .SHORT: return "Short"
        case .MEDIUM: return "Medium"
        case .LONG: return "Long"
        }
    }

    var description: String {
        switch self {
        case .SHORT: return "Days, like bread or milk"
        case .MEDIUM: return "1 to 3 weeks, like onions or eggs"
        case .LONG: return "Months, like rice, atta or pasta"
        }
    }

    var freshDays: Int {
        switch self {
        case .SHORT: return 5
        case .MEDIUM: return 21
        case .LONG: return 180
        }
    }

    static func from(_ name: String) -> Life { Life(rawValue: name) ?? .MEDIUM }
}

enum EventType: String {
    case BOUGHT, USED, SHARED, ADJUST
    static func from(_ name: String) -> EventType { EventType(rawValue: name) ?? .ADJUST }
}

enum StockStatus { case out, low, ok }

struct Entry {
    let type: EventType
    let date: Day
    let quantity: Double
    var priceMinor: Int64? = nil
    var shop: String = ""
}

enum Categories {
    static let presets = [
        "Groceries", "Fruit & veg", "Bread & bakery", "Dairy & eggs", "Grains & flour",
        "Spices", "Snacks", "Drinks", "Toiletries", "Cleaning", "Other",
    ]

    private static let emoji: [String: String] = [
        "groceries": "🛒", "fruit & veg": "🥕", "bread & bakery": "🍞", "dairy & eggs": "🥛",
        "grains & flour": "🌾", "spices": "🌶️", "snacks": "🍪", "drinks": "🥤",
        "toiletries": "🧴", "cleaning": "🧽", "other": "📦",
    ]

    static func emojiFor(_ category: String) -> String {
        emoji[category.trimmingCharacters(in: .whitespaces).lowercased()] ?? "🫙"
    }

    /// Presets in order, then the user's own alphabetically.
    static func ordered(_ used: [String]) -> [String] {
        let lowered = Set(used.map { $0.lowercased() })
        var seen = Set<String>()
        var custom: [String] = []
        for c in used where !presets.contains(where: { $0.lowercased() == c.lowercased() }) {
            if seen.insert(c.lowercased()).inserted { custom.append(c) }
        }
        return presets.filter { lowered.contains($0.lowercased()) } + custom.sorted { $0.lowercased() < $1.lowercased() }
    }
}

enum Shops {
    static let presets = ["IKI", "Norfa", "Maxima", "Lidl", "Rimi", "Pepco"]

    static func options(_ used: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for name in presets + used.map({ $0.trimmingCharacters(in: .whitespaces) }) where !name.isEmpty {
            if seen.insert(name.lowercased()).inserted { result.append(name) }
        }
        return result
    }
}

enum Qty {
    static func round(_ q: Double) -> Double { (q * 1000).rounded() / 1000 }

    static func format(_ q: Double) -> String {
        let r = round(q)
        if r == r.rounded() { return String(Int64(r)) }
        var s = String(format: "%.3f", r)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    /// Rounded for estimates: whole numbers from 2 up, otherwise one decimal.
    static func approx(_ q: Double) -> String {
        q >= 2 ? String(Int64(q.rounded())) : format((q * 10).rounded() / 10)
    }

    static func parse(_ text: String) -> Double? {
        guard let v = Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")),
              v > 0, v <= 10_000, !v.isNaN else { return nil }
        return round(v)
    }
}

// MARK: - Pantry logic

enum Pantry {
    static func stock(_ entries: [Entry]) -> Double {
        Qty.round(entries.reduce(0) { sum, e in
            switch e.type {
            case .BOUGHT, .ADJUST: return sum + e.quantity
            case .USED, .SHARED: return sum - e.quantity
            }
        })
    }

    /// Default "uses per tap": half a kilo or litre, otherwise one.
    static func defaultStep(_ unit: QtyUnit) -> Double { unit == .KG || unit == .LITRE ? 0.5 : 1 }

    /// What one tap of − or Shared takes: the item's step, never more than what's left.
    static func useAmount(_ step: Double, _ stock: Double) -> Double { max(0, Qty.round(min(max(step, 0.001), stock))) }

    /// What one tap of ＋ adds: a whole pack if it comes in packs; for kilos and litres, the size you usually buy; otherwise one.
    static func buyAmount(_ unit: QtyUnit, _ entries: [Entry], packSize: Double = 0) -> Double {
        if packSize > 0 { return packSize }
        guard unit == .KG || unit == .LITRE, let q = lastBought(entries)?.quantity, q > 0 else { return 1 }
        return q
    }

    static func expectedPriceMinor(_ unit: QtyUnit, _ entries: [Entry], packSize: Double = 0) -> Int64? {
        priceFor(entries, buyAmount(unit, entries, packSize: packSize))
    }

    /// The price of [quantity] at the last price paid, from the whole purchase and rounded once.
    static func priceFor(_ entries: [Entry], _ quantity: Double) -> Int64? {
        guard let last = lastPriced(entries), let paid = last.priceMinor, last.quantity > 0 else { return nil }
        return Int64((Double(paid) * quantity / last.quantity).rounded(.toNearestOrAwayFromZero))
    }

    static func packLabel(_ unit: QtyUnit, _ packSize: Double) -> String {
        packSize > 0 ? "1 pack = \(unit.label(packSize))" : ""
    }

    static func packsHint(_ stock: Double, _ packSize: Double) -> String {
        guard packSize > 0, stock > 0 else { return "" }
        let packs = stock / packSize
        let text = packs >= 2 ? String(Int64(packs.rounded())) : Qty.format((packs * 10).rounded() / 10)
        return "about \(text) pack\(text == "1" ? "" : "s")"
    }

    static func lastBought(_ entries: [Entry]) -> Entry? {
        entries.filter { $0.type == .BOUGHT }.max { $0.date < $1.date }
    }

    static func lastPriced(_ entries: [Entry]) -> Entry? {
        entries.filter { $0.type == .BOUGHT && $0.priceMinor != nil && $0.quantity > 0 }.max { $0.date < $1.date }
    }

    static func unitPriceMinor(_ e: Entry) -> Int64? {
        guard let price = e.priceMinor, e.quantity > 0 else { return nil }
        return Int64((Double(price) / e.quantity).rounded(.toNearestOrAwayFromZero))
    }

    static func totalMinor(_ unitPrice: Int64, _ quantity: Double) -> Int64 {
        Int64((Double(unitPrice) * quantity).rounded(.toNearestOrAwayFromZero))
    }

    static func dailyUse(_ entries: [Entry], _ today: Day, windowDays: Int = 60) -> Double? {
        let from = today.plusDays(-windowDays)
        let uses = entries.filter { ($0.type == .USED || $0.type == .SHARED) && !($0.date < from) && !($0.date > today) }
        guard uses.count >= 2, let first = uses.map(\.date).min() else { return nil }
        let days = today.epochDay - first.epochDay + 1
        guard days >= 2 else { return nil }
        return uses.reduce(0) { $0 + $1.quantity } / Double(days)
    }

    static func daysLeft(_ entries: [Entry], _ today: Day) -> Int? {
        let s = stock(entries)
        if s <= 0 { return 0 }
        guard let rate = dailyUse(entries, today), rate > 0 else { return nil }
        return Int(s / rate)
    }

    static func status(_ entries: [Entry], lowAt: Double, _ today: Day) -> StockStatus {
        let s = stock(entries)
        if s <= 0 { return .out }
        if s <= lowAt { return .low }
        if let left = daysLeft(entries, today), left <= 2 { return .low }
        return .ok
    }

    static func checkFreshness(_ entries: [Entry], _ life: Life, _ today: Day) -> Bool {
        if life == .LONG || stock(entries) <= 0 { return false }
        guard let last = lastBought(entries) else { return false }
        return today.epochDay - last.date.epochDay > life.freshDays
    }

    /// Monday of the week [day] is in. 1 January 1970 was a Thursday.
    static func weekStart(_ day: Day) -> Day {
        let index = ((day.epochDay + 3) % 7 + 7) % 7
        return day.plusDays(-index)
    }

    struct ItemWeek {
        let itemId: Int64
        let used: Double
        let shared: Double
        let bought: Double
        let spentMinor: Int64
    }

    struct Week {
        let start: Day
        let items: [ItemWeek]
        let spentMinor: Int64
        let spentByCategory: [String: Int64]
        let usedTotal: Double
        let sharedTotal: Double
        let itemsUsed: Int
        let timesShared: Int
        let itemsBought: Int
    }

    static func week(_ start: Day, _ entriesByItem: [Int64: [Entry]], categoryOf: (Int64) -> String) -> Week {
        let end = start.plusDays(6)
        var items: [ItemWeek] = []
        for (id, entries) in entriesByItem {
            let inWeek = entries.filter { !($0.date < start) && !($0.date > end) }
            if inWeek.isEmpty { continue }
            let used = inWeek.filter { $0.type == .USED || $0.type == .SHARED }.reduce(0) { $0 + $1.quantity }
            let shared = inWeek.filter { $0.type == .SHARED }.reduce(0) { $0 + $1.quantity }
            let boughtEntries = inWeek.filter { $0.type == .BOUGHT }
            let w = ItemWeek(
                itemId: id,
                used: Qty.round(used),
                shared: Qty.round(shared),
                bought: Qty.round(boughtEntries.reduce(0) { $0 + $1.quantity }),
                spentMinor: boughtEntries.reduce(0) { $0 + ($1.priceMinor ?? 0) }
            )
            if w.used == 0 && w.bought == 0 { continue }
            items.append(w)
        }
        let timesShared = entriesByItem.values.reduce(0) { sum, entries in
            sum + entries.filter { $0.type == .SHARED && !($0.date < start) && !($0.date > end) }.count
        }
        var byCategory: [String: Int64] = [:]
        for w in items where w.spentMinor > 0 { byCategory[categoryOf(w.itemId), default: 0] += w.spentMinor }
        items.sort { a, b in a.used != b.used ? a.used > b.used : a.spentMinor > b.spentMinor }
        return Week(
            start: start,
            items: items,
            spentMinor: items.reduce(0) { $0 + $1.spentMinor },
            spentByCategory: byCategory,
            usedTotal: Qty.round(items.reduce(0) { $0 + $1.used }),
            sharedTotal: Qty.round(items.reduce(0) { $0 + $1.shared }),
            itemsUsed: items.filter { $0.used > 0 }.count,
            timesShared: timesShared,
            itemsBought: items.filter { $0.bought > 0 }.count
        )
    }

    static func daysAgoLabel(_ date: Day, _ today: Day) -> String {
        let days = today.epochDay - date.epochDay
        switch days {
        case ..<0: return "on \(date.format(.short))"
        case 0: return "today"
        case 1: return "yesterday"
        case 2..<14: return "\(days) days ago"
        case 14..<60: return "\(days / 7) weeks ago"
        default: return "\(days / 30) months ago"
        }
    }
}

// MARK: - Money

enum Money {
    static let currencies = ["EUR", "INR", "USD", "GBP"]

    /// "1.20", "1,2" or "3" to cents. Nil if invalid, negative or too large. Zero is allowed.
    static func parseToMinor(_ text: String) -> Int64? {
        let cleaned = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "").replacingOccurrences(of: ",", with: ".")
        if cleaned.isEmpty { return nil }
        let parts = cleaned.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count > 2 { return nil }
        let whole = String(parts[0])
        let fraction = parts.count == 2 ? String(parts[1]) : ""
        if whole.isEmpty && fraction.isEmpty { return nil }
        if !(whole + fraction).allSatisfy({ $0.isASCII && $0.isNumber }) { return nil }
        if fraction.count > 2 || whole.count > 9 { return nil }
        let minor = (Int64(whole.isEmpty ? "0" : whole) ?? 0) * 100 + (Int64((fraction + "00").prefix(2)) ?? 0)
        return minor > 100_000_000 ? nil : minor
    }

    static func minorToInput(_ minor: Int64) -> String {
        let whole = minor / 100, cents = minor % 100
        if cents == 0 { return "\(whole)" }
        if cents % 10 == 0 { return "\(whole).\(cents / 10)" }
        return String(format: "%lld.%02lld", whole, cents)
    }

    static func format(_ minor: Int64, _ currency: String) -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        switch currency {
        case "INR": f.locale = Locale(identifier: "en_IN")
        case "EUR": f.locale = Locale(identifier: "en_IE")
        case "GBP": f.locale = Locale(identifier: "en_GB")
        default: f.locale = Locale(identifier: "en_US")
        }
        f.currencyCode = currency
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        let value = NSDecimalNumber(mantissa: UInt64(abs(minor)), exponent: -2, isNegative: minor < 0)
        return f.string(from: value) ?? "\(currency) \(minorToInput(minor))"
    }
}

enum Badges {
    private static let palette: [UInt32] = [0x2E7D32, 0x00897B, 0x6D4C41, 0xEF6C00, 0x3949AB, 0xAD1457, 0x00838F, 0x7B4DFF]

    /// Java's String.hashCode, so a name gets the same colour as on Android.
    static func javaHash(_ s: String) -> Int32 {
        var h: Int32 = 0
        for unit in s.utf16 { h = h &* 31 &+ Int32(unit) }
        return h
    }

    static func colorFor(_ name: String) -> UInt32 {
        let h = Int(javaHash(name.trimmingCharacters(in: .whitespaces).lowercased()))
        return palette[((h % palette.count) + palette.count) % palette.count]
    }

    static func initial(_ name: String) -> String {
        guard let c = name.trimmingCharacters(in: .whitespaces).first(where: { $0.isLetter || $0.isNumber }) else { return "?" }
        return String(c).uppercased()
    }
}
