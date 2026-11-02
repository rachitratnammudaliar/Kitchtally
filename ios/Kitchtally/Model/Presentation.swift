import Foundation

// Words shown for an item, the same as the Android app's Screens.kt.

func stockLabel(_ item: Item, _ stock: Double) -> String {
    stock <= 0 ? "Out" : item.unitValue.leftLabel(stock)
}

/// "about 6 meals", or nil if the item doesn't count meals or they aren't set yet.
func mealsText(_ item: Item, _ stock: Double) -> String? {
    guard item.countMeals, item.mealsPerUnit > 0, stock > 0 else { return nil }
    return Meals.label(Meals.left(stock, item.mealsPerUnit))
}

private func capitalized(_ s: String) -> String { s.prefix(1).uppercased() + s.dropFirst() }

/// Almost out first; then meals left; then freshness, days left or when it was last bought.
@MainActor
func detailLine(_ item: Item, _ store: Store, _ today: Day) -> String {
    let entries = store.entries(item.id)
    let stock = Pantry.stock(entries)
    let status = store.status(item, today)
    let meals = mealsText(item, stock)
    let listed = store.onShoppingList(item, today) ? " · on your To buy list" : ""
    if stock > 0 && status == .last { return "Last meal left" + listed }
    if stock > 0 && status == .low {
        return ([meals.map(capitalized), meals == nil ? "Almost out" : "almost out"].compactMap { $0 }).joined(separator: " · ") + listed
    }
    let last = Pantry.lastBought(entries)
    var rest = "not bought yet"
    if Pantry.checkFreshness(entries, item.lifeValue, today), let last = last {
        rest = "bought \(Pantry.daysAgoLabel(last.date, today)) · check it's fresh"
    } else if stock > 0, !item.unitValue.isLevel, meals == nil, let left = Pantry.daysLeft(entries, today) {
        rest = left == 0 ? "likely runs out today" : left == 1 ? "about 1 day left" : "about \(left) days left"
    } else if let last = last {
        rest = "bought \(Pantry.daysAgoLabel(last.date, today))" + (last.shop.isEmpty ? "" : " at \(last.shop)")
    }
    return capitalized(([meals, rest].compactMap { $0 }).joined(separator: " · "))
}

/// Shops alphabetically, with "no shop" (empty) last.
func shopOrder(_ shops: [String]) -> [String] {
    let named = Array(Set(shops.filter { !$0.isEmpty })).sorted { $0.lowercased() < $1.lowercased() }
    return named + (shops.contains("") ? [""] : [])
}

/// The To buy list as text, grouped by shop, for WhatsApp and the like.
@MainActor
func shoppingText(_ store: Store, _ today: Day) -> String {
    let list = store.items.filter { store.onShoppingList($0, today) }
    let byShop = Dictionary(grouping: list) { Pantry.lastShop(store.entries($0.id)) }
    var text = "Shopping list"
    for shop in shopOrder(Array(byShop.keys)) {
        text += "\n\n" + (shop.isEmpty ? "Anywhere" : shop)
        for item in (byShop[shop] ?? []).sorted(by: { $0.name.lowercased() < $1.name.lowercased() }) {
            let amount = Pantry.buyAmount(item.unitValue, store.entries(item.id), packSize: item.packSize)
            text += "\n• \(item.name) (\(item.unitValue.label(amount)))"
        }
    }
    if !store.notes.isEmpty {
        text += "\n\nAlso need"
        for n in store.notes { text += "\n• \(n.text)" }
    }
    return text
}

func historyTitle(_ e: Event, _ unit: QtyUnit) -> String {
    let qty = unit.label(abs(e.quantity))
    let sign = e.quantity < 0 ? "−" : "+"
    switch e.typeValue {
    case .BOUGHT: return "Bought \(qty)"
    case .USED: return "Used \(qty)"
    case .SHARED: return "Shared \(qty) with friends"
    case .ADJUST: return e.note.isEmpty ? "Stock changed by \(sign)\(qty)" : "\(e.note): \(sign)\(qty)"
    }
}
