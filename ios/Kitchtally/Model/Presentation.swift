import Foundation

// Words shown for an item, the same as the Android app's Screens.kt.

func stockLabel(_ item: Item, _ stock: Double) -> String {
    stock <= 0 ? "Out" : "\(item.unitValue.label(stock)) left"
}

@MainActor
func detailLine(_ item: Item, _ store: Store, _ today: Day) -> String {
    let entries = store.entries(item.id)
    let last = Pantry.lastBought(entries)
    if Pantry.checkFreshness(entries, item.lifeValue, today), let last = last {
        return "Bought \(Pantry.daysAgoLabel(last.date, today)) · check it's fresh"
    }
    if let left = Pantry.daysLeft(entries, today), Pantry.stock(entries) > 0 {
        switch left {
        case 0: return "Likely runs out today"
        case 1: return "About 1 day left"
        default: return "About \(left) days left"
        }
    }
    if let last = last {
        return "Bought \(Pantry.daysAgoLabel(last.date, today))" + (last.shop.isEmpty ? "" : " at \(last.shop)")
    }
    return "Not bought yet"
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
