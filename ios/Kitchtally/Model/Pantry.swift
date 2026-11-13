import Foundation

// MARK: - Units, life, events

enum QtyUnit: String, CaseIterable, Identifiable {
    // Same order as Android's "Counted in" chips.
    case PIECE, PACKET, BOTTLE, BOX, BAG, LEVEL, GRAM, KG, ML, LITRE
    var id: String { rawValue }

    var singular: String {
        switch self {
        case .PIECE: return "piece"
        case .PACKET: return "packet"
        case .BOTTLE: return "bottle"
        case .BOX: return "box"
        case .BAG: return "bag"
        case .LEVEL: return "full one"
        case .GRAM: return "g"
        case .KG: return "kg"
        case .ML: return "ml"
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
        case .LEVEL: return "full ones"
        case .GRAM: return "g"
        case .KG: return "kg"
        case .ML: return "ml"
        case .LITRE: return "litres"
        }
    }

    /// Name on the "Counted in" choices.
    var title: String {
        switch self {
        case .LEVEL: return "% left"
        case .GRAM: return "Grams"
        case .KG: return "Kg"
        case .ML: return "Ml"
        default: return plural.prefix(1).uppercased() + plural.dropFirst()
        }
    }

    var priceLabel: String {
        switch self {
        case .PIECE, .LEVEL: return "each"
        case .KG, .GRAM: return "per kg"
        case .LITRE, .ML: return "per litre"
        default: return "per \(singular)"
        }
    }

    /// Prices are shown per kg or litre, so grams and millilitres divide by 1000.
    var priceDivisor: Double { self == .GRAM || self == .ML ? 1000 : 1 }
    var isLevel: Bool { self == .LEVEL }
    var byWeight: Bool { self == .GRAM || self == .KG || self == .ML || self == .LITRE }

    /// An amount: "2 packets", "500 g", and for % items "25%", "1 full", "1 full + 50%".
    func label(_ qty: Double) -> String {
        guard isLevel else {
            if !byWeight {
                // People say "half a packet" and "a little", not 0.5 or 0.1.
                if qty > 0 && qty < 0.2 { return "a little" }
                if let f = Qty.fraction(qty) { return "\(f) \(qty <= 1 ? singular : plural)" }
            }
            return "\(Qty.format(qty)) \(qty == 1 ? singular : plural)"
        }
        let whole = Int((Qty.round(qty) + 1e-9).rounded(.down))
        let pct = Qty.percent(qty - Double(whole))
        if whole == 0 { return "\(pct)%" }
        return pct == 0 ? "\(whole) full" : "\(whole) full + \(pct)%"
    }

    /// What's left: "2 packets left", "80% left", "Full", "2 full".
    func leftLabel(_ stock: Double) -> String {
        if !isLevel { return "\(label(stock)) left" }
        if Qty.round(stock) == 1 { return "Full" }
        return stock < 1 ? "\(label(stock)) left" : label(stock)
    }

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
        case .SHORT: return "2 to 3 days, like bread or milk"
        case .MEDIUM: return "1 week to 10 days, like vegetables or eggs"
        case .LONG: return "2 weeks to a month or more, like rice, salt or oil. No freshness reminder"
        }
    }

    var freshDays: Int {
        switch self {
        case .SHORT: return 3
        case .MEDIUM: return 10
        case .LONG: return 30
        }
    }

    static func from(_ name: String) -> Life { Life(rawValue: name) ?? .MEDIUM }
}

enum EventType: String {
    case BOUGHT, USED, SHARED, ADJUST
    static func from(_ name: String) -> EventType { EventType(rawValue: name) ?? .ADJUST }
}

/// out: none left. last: only one meal left (red). low: running low. ok: plenty.
enum StockStatus: Int { case out, last, low, ok }

/// How an item is counted and when it's low.
struct Counting {
    var unit: QtyUnit = .PIECE
    var lowAt: Double = 1
    /// Meals one unit makes (one full packet for % items); 0 if not counting meals.
    var mealsPerUnit: Double = 0
    var lowInMeals = false
    var countsMeals: Bool { mealsPerUnit > 0 }
}

enum Meals {
    static func left(_ stock: Double, _ mealsPerUnit: Double) -> Double { Qty.round(max(0, stock) * mealsPerUnit) }

    /// "about 6 meals", "1 meal", "less than 1 meal", "no meals".
    static func label(_ meals: Double) -> String {
        let whole = Int64((Qty.round(meals) + 1e-9).rounded(.down))
        if meals <= 0 { return "no meals" }
        if whole == 0 { return "less than 1 meal" }
        if whole == 1 { return Qty.round(meals) > 1 ? "1 to 2 meals" : "1 meal" }
        return "about \(whole) meals"
    }

    /// What cooking one meal takes: one meal's worth when meals are counted, otherwise one tap.
    static func cookAmount(_ step: Double, _ mealsPerUnit: Double) -> Double {
        mealsPerUnit > 0 ? max(0.001, Qty.round(1 / mealsPerUnit)) : step
    }

    /// Meals per unit from "these 5 onions make about 8 meals".
    static func perUnit(_ quantity: Double, _ meals: Double) -> Double? {
        guard quantity > 0, meals > 0 else { return nil }
        return (meals / quantity * 10_000).rounded() / 10_000
    }
}

struct Entry {
    let type: EventType
    let date: Day
    let quantity: Double
    var priceMinor: Int64? = nil
    var shop: String = ""
    /// A purchase made for next week counts from this day on the Weekly tab.
    var countsFrom: Day? = nil
    /// Bought on a craving: an avoidable expense.
    var craving: Bool = false

    /// The day this entry counts on the Weekly tab: purchases may be moved to next week.
    var weekDate: Day { type == .BOUGHT ? (countsFrom ?? date) : date }
}

enum Categories {
    static let presets = [
        "Vegetables", "Fruit", "Staples", "Sauces & spreads", "Dairy & eggs", "Meat & protein",
        "Bread & bakery", "Spices", "Snacks", "Drinks", "Personal care", "Household", "Other",
    ]

    /// Things that last a month or more and aren't food: counted in Budget, not in the weekly shop.
    static func isLongLasting(_ category: String) -> Bool {
        ["personal care", "household"].contains(category.lowercased())
    }

    /// Names used before version 1.2, and what they're called now.
    static let renamed = ["Fruit & veg": "Vegetables", "Grains & flour": "Staples", "Toiletries": "Personal care", "Cleaning": "Household"]

    private static let emoji: [String: String] = [
        "vegetables": "🥬", "fruit": "🍎", "staples": "🌾", "sauces & spreads": "🥫",
        "dairy & eggs": "🥛", "meat & protein": "🍗", "bread & bakery": "🍞", "spices": "🌶️",
        "snacks": "🍪", "drinks": "🥤", "personal care": "🧴", "household": "🧻", "other": "📦",
        "groceries": "🛒",
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

/// What a new item probably is, from its name. The rules are the same as the Android app's.
struct Suggestion: Equatable {
    let category: String
    let unit: QtyUnit
    let life: Life
    var packSize: Double = 0
    var countMeals: Bool = false
}

enum Suggestions {
    // Checked in order, so more specific names come first.
    private static let rules: [([String], Suggestion)] = [
        (["ice cream"], Suggestion(category: "Snacks", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["toilet paper", "toilet roll", "tissue", "kitchen roll", "paper towel", "napkin"], Suggestion(category: "Household", unit: .PACKET, life: .LONG, packSize: 0, countMeals: false)),
        (["sponge", "scrub", "bin bag", "trash bag", "garbage bag", "foil", "cling", "baking paper"], Suggestion(category: "Household", unit: .PACKET, life: .LONG, packSize: 0, countMeals: false)),
        (["dishwash", "dish wash", "dish soap", "washing liquid", "washing up", "detergent", "laundry", "cleaner", "bleach"], Suggestion(category: "Household", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["toothbrush", "razor", "shaving", "sanitary", "pads", "cotton"], Suggestion(category: "Personal care", unit: .PIECE, life: .LONG, packSize: 0, countMeals: false)),
        (["body wash", "shower gel", "soap", "shampoo", "conditioner", "toothpaste", "mouthwash", "face wash", "facewash", "cleanser", "body cream", "face cream", "hand cream", "night cream", "day cream", "lotion", "moisturiser", "moisturizer", "sunscreen", "deodorant", "deo", "lip balm", "perfume", "cologne", "fragrance", "body spray", "hair oil", "hair gel"], Suggestion(category: "Personal care", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["sauce", "puree", "purée", "ketchup", "mayo", "mayonnaise", "jam", "pickle", "achar", "chutney", "dressing", "honey", "peanut butter", "nutella", "spread", "tomato paste", "ginger garlic paste", "salsa", "pesto"], Suggestion(category: "Sauces & spreads", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["veggie", "vegetable", "frozen mix", "mushroom", "spinach", "salad", "lettuce", "herbs", "coriander leaves", "mint"], Suggestion(category: "Vegetables", unit: .LEVEL, life: .MEDIUM, packSize: 0, countMeals: true)),
        (["chilli powder", "chili powder", "chilli flakes", "chili flakes"], Suggestion(category: "Spices", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["garlic", "ginger"], Suggestion(category: "Vegetables", unit: .PIECE, life: .LONG, packSize: 0, countMeals: false)),
        (["onion", "potato", "carrot", "tomato", "cucumber", "capsicum", "bell pepper", "cabbage", "cauliflower", "broccoli", "pumpkin", "green peas", "beans", "zucchini", "courgette", "eggplant", "aubergine", "brinjal", "beetroot", "radish", "okra", "bhindi", "chilli", "chili"], Suggestion(category: "Vegetables", unit: .PIECE, life: .MEDIUM, packSize: 0, countMeals: true)),
        (["apple", "banana", "orange", "grape", "mango", "kiwi", "pear", "berr", "strawberr", "blueberr", "raspberr", "lemon", "lime", "melon", "pineapple", "avocado", "pomegranate", "plum", "peach"], Suggestion(category: "Fruit", unit: .PIECE, life: .MEDIUM, packSize: 0, countMeals: false)),
        (["egg"], Suggestion(category: "Dairy & eggs", unit: .PIECE, life: .MEDIUM, packSize: 10.0, countMeals: true)),
        (["milk", "substitute", "oat drink", "soy drink", "almond drink", "oatly"], Suggestion(category: "Dairy & eggs", unit: .LEVEL, life: .SHORT, packSize: 0, countMeals: false)),
        (["yogurt", "yoghurt", "curd", "dahi", "kefir", "skyr"], Suggestion(category: "Dairy & eggs", unit: .PIECE, life: .MEDIUM, packSize: 0, countMeals: false)),
        (["cheese", "butter", "ghee", "paneer", "cream"], Suggestion(category: "Dairy & eggs", unit: .LEVEL, life: .MEDIUM, packSize: 0, countMeals: false)),
        (["chicken", "fish", "meat", "mutton", "lamb", "beef", "pork", "turkey", "salmon", "tuna", "prawn", "shrimp", "tofu", "sausage", "ham", "keema", "mince"], Suggestion(category: "Meat & protein", unit: .PACKET, life: .SHORT, packSize: 0, countMeals: true)),
        (["bread", "bun", "roti", "naan", "tortilla", "wrap", "croissant", "bagel", "pav"], Suggestion(category: "Bread & bakery", unit: .LEVEL, life: .SHORT, packSize: 0, countMeals: false)),
        (["masala", "spice", "turmeric", "haldi", "cumin", "jeera", "chilli powder", "chili powder", "pepper", "garam", "coriander powder", "cinnamon", "oregano", "paprika", "seasoning", "cardamom", "clove", "mustard seed", "hing"], Suggestion(category: "Spices", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["rice", "atta", "flour", "maida", "besan", "suji", "sooji", "semolina", "poha", "quinoa"], Suggestion(category: "Staples", unit: .KG, life: .LONG, packSize: 0, countMeals: true)),
        (["oil"], Suggestion(category: "Staples", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["salt", "sugar"], Suggestion(category: "Staples", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["oats", "cereal", "cornflakes", "muesli"], Suggestion(category: "Staples", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: true)),
        (["pasta", "spaghetti", "spagheti", "macaroni", "penne", "noodle", "maggi", "chole", "chana", "chickpea", "rajma", "dal", "daal", "lentil", "vermicelli"], Suggestion(category: "Staples", unit: .PACKET, life: .LONG, packSize: 0, countMeals: true)),
        (["coffee", "tea", "chai"], Suggestion(category: "Drinks", unit: .LEVEL, life: .LONG, packSize: 0, countMeals: false)),
        (["juice", "water", "soda", "cola", "coke", "sprite", "beer", "wine", "energy drink", "lassi"], Suggestion(category: "Drinks", unit: .BOTTLE, life: .MEDIUM, packSize: 0, countMeals: false)),
        (["chips", "crisps", "biscuit", "cookie", "chocolate", "namkeen", "bhujia", "popcorn", "nuts", "almond", "cashew", "peanut", "candy", "sweets", "cake"], Suggestion(category: "Snacks", unit: .PACKET, life: .LONG, packSize: 0, countMeals: false)),
    ]

    /// True if [keyword] starts a word in [text]: "egg" matches "Eggs" but "peas" doesn't match "chickpeas".
    private static func startsWord(_ text: String, _ keyword: String) -> Bool {
        var searchStart = text.startIndex
        while let range = text.range(of: keyword, range: searchStart..<text.endIndex) {
            if range.lowerBound == text.startIndex || !text[text.index(before: range.lowerBound)].isLetter { return true }
            searchStart = text.index(after: range.lowerBound)
        }
        return false
    }

    static func forName(_ name: String) -> Suggestion? {
        let n = name.trimmingCharacters(in: .whitespaces).lowercased()
        guard !n.isEmpty else { return nil }
        return rules.first { keys, _ in keys.contains { startsWord(n, $0) } }?.1
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

    /// ¼ ½ ¾ and "1½", for amounts that are a whole number plus a quarter, half or three quarters. Nil otherwise.
    static func fraction(_ q: Double) -> String? {
        let nearest = (q * 4).rounded() / 4
        if abs(q - nearest) > 0.02 || nearest <= 0 { return nil }
        let whole = Int(nearest.rounded(.down))
        let rest = nearest - Double(whole)
        let symbol: String
        if rest == 0.25 { symbol = "¼" } else if rest == 0.5 { symbol = "½" } else if rest == 0.75 { symbol = "¾" } else { return nil }
        return whole == 0 ? symbol : "\(whole)\(symbol)"
    }

    /// 0.8 -> 80.
    static func percent(_ fraction: Double) -> Int { Int((fraction * 100).rounded(.toNearestOrAwayFromZero)) }

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

    /// Default "uses per tap": half a kilo or litre, 100 g or ml, a quarter of a % item, otherwise one.
    static func defaultStep(_ unit: QtyUnit) -> Double {
        switch unit {
        case .KG, .LITRE: return 0.5
        case .GRAM, .ML: return 100
        case .LEVEL: return 0.25
        default: return 1
        }
    }

    /// Default "running low at": 25% for % items, 200 g or ml, half a kilo or litre, otherwise one.
    static func defaultLowAt(_ unit: QtyUnit) -> Double {
        switch unit {
        case .LEVEL: return 0.25
        case .GRAM, .ML: return 200
        case .KG, .LITRE: return 0.5
        default: return 1
        }
    }

    static let defaultLowMeals = 2.0

    /// What one tap of − or Shared takes: the item's step, never more than what's left.
    static func useAmount(_ step: Double, _ stock: Double) -> Double { max(0, Qty.round(min(max(step, 0.001), stock))) }

    /// What one tap of ＋ adds: a whole pack if it comes in packs; for kilos and litres, the size you usually buy; otherwise one.
    static func buyAmount(_ unit: QtyUnit, _ entries: [Entry], packSize: Double = 0) -> Double {
        if packSize > 0 { return packSize }
        guard unit.byWeight else { return 1 }
        if let q = lastBought(entries)?.quantity, q > 0 { return q }
        return unit.priceDivisor > 1 ? 500 : 1
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

    /// Price per unit; grams and ml give per kg and per litre.
    static func unitPriceMinor(_ e: Entry, _ unit: QtyUnit = .PIECE) -> Int64? {
        guard let price = e.priceMinor, e.quantity > 0 else { return nil }
        return Int64((Double(price) * unit.priceDivisor / e.quantity).rounded(.toNearestOrAwayFromZero))
    }

    /// 1.5 kg at €1.20/kg = €1.80; 500 g at €2/kg = €1.
    static func totalMinor(_ unitPrice: Int64, _ quantity: Double, _ unit: QtyUnit = .PIECE) -> Int64 {
        Int64((Double(unitPrice) * quantity / unit.priceDivisor).rounded(.toNearestOrAwayFromZero))
    }

    /// The shop it was last bought at, or "".
    static func lastShop(_ entries: [Entry]) -> String {
        entries.filter { $0.type == .BOUGHT && !$0.shop.trimmingCharacters(in: .whitespaces).isEmpty }
            .max { $0.date < $1.date }?.shop.trimmingCharacters(in: .whitespaces) ?? ""
    }

    struct Cheaper: Equatable {
        let shop: String
        let unitPriceMinor: Int64
    }

    /// The cheapest other shop by its latest unit price in the last [days] days, if cheaper.
    static func cheaperElsewhere(_ entries: [Entry], _ unit: QtyUnit, _ unitPrice: Int64, shop: String, _ today: Day, days: Int = 180) -> Cheaper? {
        let from = today.plusDays(-days)
        let mine = shop.trimmingCharacters(in: .whitespaces).lowercased()
        let others = entries.filter {
            let s = $0.shop.trimmingCharacters(in: .whitespaces)
            return $0.type == .BOUGHT && !s.isEmpty && s.lowercased() != mine && !($0.date < from)
        }
        var best: Cheaper?
        for (_, list) in Dictionary(grouping: others, by: { $0.shop.trimmingCharacters(in: .whitespaces).lowercased() }) {
            guard let latest = list.filter({ $0.priceMinor != nil }).max(by: { $0.date < $1.date }),
                  let price = unitPriceMinor(latest, unit), price < unitPrice else { continue }
            if best == nil || price < best!.unitPriceMinor {
                best = Cheaper(shop: latest.shop.trimmingCharacters(in: .whitespaces), unitPriceMinor: price)
            }
        }
        return best
    }

    /// Total paid from [from] to [to], both included.
    static func spentBetween(_ entriesByItem: [Int64: [Entry]], _ from: Day, _ to: Day) -> Int64 {
        entriesByItem.values.reduce(0) { sum, entries in
            sum + entries.filter { $0.type == .BOUGHT && !($0.date < from) && !($0.date > to) }.reduce(0) { $0 + ($1.priceMinor ?? 0) }
        }
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
        status(entries, Counting(lowAt: lowAt), today)
    }

    /// Same rules as Android: red at one meal; low at "running low at" (meals or the unit);
    /// pieces, packets and kilos also when likely to run out within 2 days.
    static func status(_ entries: [Entry], _ c: Counting, _ today: Day) -> StockStatus {
        let s = stock(entries)
        if s <= 0 { return .out }
        if c.countsMeals {
            let meals = Meals.left(s, c.mealsPerUnit)
            if meals <= 1 { return .last }
            if c.lowInMeals && meals <= c.lowAt { return .low }
        }
        if !(c.lowInMeals && c.countsMeals) && s <= c.lowAt { return .low }
        if c.unit.isLevel || c.countsMeals { return .ok }
        // "Remind me at 0" means only when it's gone: no guessing from how fast you use it.
        if c.lowAt <= 0 { return .ok }
        if let left = daysLeft(entries, today), left <= 2 { return .low }
        return .ok
    }

    static func checkFreshness(_ entries: [Entry], _ life: Life, _ today: Day) -> Bool {
        if life == .LONG || stock(entries) <= 0 { return false }
        guard let last = lastBought(entries) else { return false }
        return today.epochDay - last.date.epochDay > life.freshDays
    }

    /// First day of the week [day] is in. [first] is 1 for Monday up to 7 for Sunday. 1 January 1970 was a Thursday.
    /// After "Start new week", weeks run seven days from [anchor] (the day you pressed it); before that, from the chosen [first] day.
    static func weekStart(_ day: Day, first: Int = 1, anchor: Day? = nil) -> Day {
        if let a = anchor, !(day < a) {
            return a.plusDays((day.epochDay - a.epochDay) / 7 * 7)
        }
        let index = ((day.epochDay + 3 - (first - 1)) % 7 + 7) % 7
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
        /// Long-lasting things bought this week (not in the numbers above).
        var longItems: [ItemWeek] = []
        var longSpentMinor: Int64 = 0
        /// Spent this week on things bought on a craving (an avoidable expense).
        var cravingMinor: Int64 = 0
    }

    static func week(_ start: Day, _ entriesByItem: [Int64: [Entry]], categoryOf: (Int64) -> String, isLong: (Int64) -> Bool = { _ in false }) -> Week {
        let end = start.plusDays(6)
        var all: [ItemWeek] = []
        for (id, entries) in entriesByItem {
            let inWeek = entries.filter { !($0.weekDate < start) && !($0.weekDate > end) }
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
            all.append(w)
        }
        // Long-lasting things (face cream, toilet paper) are counted in Budget, not in the weekly shop.
        let longItems = all.filter { isLong($0.itemId) }.sorted { $0.spentMinor != $1.spentMinor ? $0.spentMinor > $1.spentMinor : $0.used > $1.used }
        var items = all.filter { !isLong($0.itemId) }
        let timesShared = entriesByItem.values.reduce(0) { sum, entries in
            sum + entries.filter { $0.type == .SHARED && !($0.weekDate < start) && !($0.weekDate > end) }.count
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
            itemsBought: items.filter { $0.bought > 0 }.count,
            longItems: longItems,
            longSpentMinor: longItems.reduce(0) { $0 + $1.spentMinor },
            cravingMinor: entriesByItem.values.reduce(0) { sum, list in
                sum + list.filter { $0.type == .BOUGHT && $0.craving && !($0.weekDate < start) && !($0.weekDate > end) }.reduce(0) { $0 + ($1.priceMinor ?? 0) }
            }
        )
    }

    struct LongBuy {
        let itemId: Int64
        let date: Day
        let quantity: Double
        let spentMinor: Int64
    }

    struct MonthSpend {
        let everydayMinor: Int64
        let longMinor: Int64
        let everydayByCategory: [String: Int64]
        let longByCategory: [String: Int64]
        let longBuys: [LongBuy]
    }

    /// What was spent in the month that starts on [first]: everyday shopping and long-lasting things apart.
    static func month(_ first: Day, _ entriesByItem: [Int64: [Entry]], categoryOf: (Int64) -> String, isLong: (Int64) -> Bool) -> MonthSpend {
        let last = first.lastOfMonth
        var everyday: Int64 = 0, long: Int64 = 0
        var everydayBy: [String: Int64] = [:], longBy: [String: Int64] = [:]
        var buys: [LongBuy] = []
        for (id, entries) in entriesByItem {
            for e in entries where e.type == .BOUGHT && !(e.weekDate < first) && !(e.weekDate > last) {
                let cost = e.priceMinor ?? 0
                if isLong(id) {
                    long += cost
                    longBy[categoryOf(id), default: 0] += cost
                    buys.append(LongBuy(itemId: id, date: e.weekDate, quantity: e.quantity, spentMinor: cost))
                } else {
                    everyday += cost
                    everydayBy[categoryOf(id), default: 0] += cost
                }
            }
        }
        return MonthSpend(
            everydayMinor: everyday, longMinor: long,
            everydayByCategory: everydayBy.filter { $0.value > 0 },
            longByCategory: longBy.filter { $0.value > 0 },
            longBuys: buys.sorted { $0.date > $1.date }
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

// MARK: - Recipes

/// An item that a planned meal still needs, and which meals want it.
struct PlannedNeed {
    let item: Item
    let recipes: [Recipe]
    let needed: Bool
}

enum RecipeStatus: Int { case ready = 0, missingOptional, missing }

enum Recipes {
    static let notPlanned = -1
    static let anyDay = 0

    /// Enough of it in the pantry for one cook (a little is enough when no amount is set).
    static func has(_ line: RecipeLine, _ stock: Double) -> Bool { stock + 1e-9 >= (line.amount > 0 ? line.amount : 0.001) }

    static func missing(_ lines: [RecipeLine], stockOf: (Int64) -> Double, needed: Bool) -> [RecipeLine] {
        lines.filter { $0.needed == needed && !has($0, stockOf($0.itemId)) }
    }

    static func status(_ lines: [RecipeLine], stockOf: (Int64) -> Double) -> RecipeStatus {
        if !missing(lines, stockOf: stockOf, needed: true).isEmpty { return .missing }
        if !missing(lines, stockOf: stockOf, needed: false).isEmpty { return .missingOptional }
        return .ready
    }

    /// Roughly what one cook costs, from the last price paid per unit. Nil when nothing has a price.
    static func cost(_ lines: [RecipeLine], unitPriceMinor: (Int64) -> Double?) -> Int64? {
        var total = 0.0
        var any = false
        for line in lines where line.amount > 0 {
            if let p = unitPriceMinor(line.itemId) { total += p * line.amount; any = true }
        }
        return any ? Int64(total.rounded()) : nil
    }

    /// Price per unit from the last priced purchase; nil if there isn't one.
    static func unitPriceMinor(_ entries: [Entry]) -> Double? {
        guard let e = Pantry.lastPriced(entries), let p = e.priceMinor, e.quantity > 0 else { return nil }
        return Double(p) / e.quantity
    }

    /// "Mon 16" for a planned day, "This week" for any day.
    static func planLabel(_ plannedDay: Int, _ today: Day) -> String {
        if plannedDay == notPlanned { return "Not planned" }
        if plannedDay == anyDay { return "This week" }
        if plannedDay == today.epochDay { return "Today" }
        if plannedDay == today.epochDay + 1 { return "Tomorrow" }
        let f = DateFormatter()
        f.dateFormat = "EEE d"
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: Day(epochDay: plannedDay).date)
    }
}
