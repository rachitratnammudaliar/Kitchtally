import Foundation
import UIKit

struct Item: Identifiable, Equatable {
    var id: Int64 = 0
    var name: String
    var category: String = "Other"
    var unit: String = QtyUnit.PACKET.rawValue
    var life: String = Life.MEDIUM.rawValue
    var lowAt: Double = 1
    var refillNeeded: Bool = false
    var photoFile: String = ""
    var notes: String = ""
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    /// How much one tap of − or Shared takes.
    var step: Double = 1
    /// Kept off the shopping list until it's bought again.
    var hiddenFromList: Bool = false
    /// Units in one pack, e.g. 10 eggs; 0 if not bought in packs.
    var packSize: Double = 0
    /// Not sure whether to buy it again: never on the shopping list until switched off.
    var unsure: Bool = false
    /// Show how many meals are left. Off for salt, oil and the like.
    var countMeals: Bool = false
    /// Meals one unit makes (one full packet for % items). 0 until set.
    var mealsPerUnit: Double = 0
    /// lowAt is counted in meals.
    var lowInMeals: Bool = false
    /// No need to buy again: never on the shopping list, shown in its own group.
    var noRebuy: Bool = false

    var unitValue: QtyUnit { QtyUnit.from(unit) }
    var counting: Counting {
        let meals = countMeals ? mealsPerUnit : 0
        return Counting(unit: unitValue, lowAt: lowAt, mealsPerUnit: meals, lowInMeals: lowInMeals && meals > 0)
    }
    var lifeValue: Life { Life.from(life) }
}

/// Something to buy that isn't a pantry item.
struct ShoppingNote: Identifiable, Equatable {
    var id: Int64 = 0
    var text: String
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
}

/// What a quick ＋ changed, so Undo can put everything back.
struct QuickBuy {
    let eventId: Int64
    let amount: Double
    let itemId: Int64
    let wasRefill: Bool
    let wasHidden: Bool
}

struct Event: Identifiable, Equatable {
    var id: Int64 = 0
    var itemId: Int64
    var type: String
    var epochDay: Int
    var quantity: Double
    var priceMinor: Int64? = nil
    var shop: String = ""
    var receiptFile: String = ""
    var note: String = ""
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    /// For a purchase: the day it starts counting on the Weekly tab (a shop for next week). Nil = the day it happened.
    var countsFromDay: Int? = nil
    /// Bought on a craving: an avoidable expense.
    var craving: Bool = false

    var typeValue: EventType { EventType.from(type) }
    var date: Day { Day(epochDay: epochDay) }
    var entry: Entry { Entry(type: typeValue, date: date, quantity: quantity, priceMinor: priceMinor, shop: shop, countsFrom: countsFromDay.map { Day(epochDay: $0) }, craving: craving) }
}

/// The same JSON format as the Android app's backups. The app's own data file also keeps photo names.
enum Backup {
    static let format = "kitchtally-backup"

    enum BackupError: LocalizedError {
        case notABackup
        var errorDescription: String? { "This file isn't a Kitchtally backup." }
    }

    static func encode(_ items: [Item], _ events: [Event], notes: [ShoppingNote] = [], includePhotos: Bool = false) -> Data {
        let ids = Set(items.map(\.id))
        let itemsJson: [[String: Any]] = items.map { i in
            var o: [String: Any] = [
                "ref": i.id, "name": i.name, "category": i.category, "unit": i.unit, "life": i.life,
                "lowAt": i.lowAt, "refillNeeded": i.refillNeeded, "notes": i.notes, "createdAt": i.createdAt,
                "step": i.step, "hiddenFromList": i.hiddenFromList, "packSize": i.packSize, "unsure": i.unsure,
                "countMeals": i.countMeals, "mealsPerUnit": i.mealsPerUnit, "lowInMeals": i.lowInMeals, "noRebuy": i.noRebuy,
            ]
            if includePhotos { o["photoFile"] = i.photoFile }
            return o
        }
        let eventsJson: [[String: Any]] = events.filter { ids.contains($0.itemId) }.map { e in
            var o: [String: Any] = [
                "item": e.itemId, "type": e.type, "epochDay": e.epochDay, "quantity": e.quantity,
                "priceMinor": e.priceMinor.map { $0 as Any } ?? NSNull(), "shop": e.shop, "note": e.note, "createdAt": e.createdAt,
                "countsFromDay": e.countsFromDay.map { $0 as Any } ?? NSNull(), "craving": e.craving,
            ]
            if includePhotos { o["receiptFile"] = e.receiptFile }
            return o
        }
        let notesJson: [[String: Any]] = notes.map { ["text": $0.text, "createdAt": $0.createdAt] }
        let root: [String: Any] = ["format": format, "version": 5, "items": itemsJson, "events": eventsJson, "shopping": notesJson]
        return (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])) ?? Data()
    }

    private static func string(_ o: [String: Any], _ key: String, _ fallback: String = "") -> String {
        (o[key] as? String) ?? fallback
    }

    private static func number(_ o: [String: Any], _ key: String) -> NSNumber? {
        guard let n = o[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n
    }

    static func decode(_ data: Data, includePhotos: Bool = false) throws -> (items: [Item], events: [Event], notes: [ShoppingNote]) {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              root["format"] as? String == format else { throw BackupError.notABackup }
        var items: [Item] = []
        for case let o as [String: Any] in root["items"] as? [Any] ?? [] {
            let name = string(o, "name").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, let ref = number(o, "ref")?.int64Value else { continue }
            var lowAt = number(o, "lowAt")?.doubleValue ?? 1
            if lowAt.isNaN || lowAt < 0 { lowAt = 1 }
            let rawCategory = string(o, "category", "Other").trimmingCharacters(in: .whitespaces)
            let category = Categories.renamed[rawCategory] ?? rawCategory
            let unit = QtyUnit.from(string(o, "unit"))
            var step = number(o, "step")?.doubleValue ?? Pantry.defaultStep(unit)
            if step.isNaN || step <= 0 { step = Pantry.defaultStep(unit) }
            items.append(Item(
                id: ref,
                name: String(name.prefix(60)),
                category: String((category.isEmpty ? "Other" : category).prefix(40)),
                unit: unit.rawValue,
                life: Life.from(string(o, "life")).rawValue,
                lowAt: min(lowAt, 10_000),
                refillNeeded: (o["refillNeeded"] as? Bool) ?? false,
                photoFile: includePhotos ? string(o, "photoFile") : "",
                notes: String(string(o, "notes").prefix(500)),
                createdAt: number(o, "createdAt")?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000),
                step: min(step, 10_000),
                hiddenFromList: (o["hiddenFromList"] as? Bool) ?? false,
                packSize: max(0, min(number(o, "packSize")?.doubleValue ?? 0, 10_000)),
                unsure: (o["unsure"] as? Bool) ?? false,
                countMeals: (o["countMeals"] as? Bool) ?? false,
                mealsPerUnit: max(0, min(number(o, "mealsPerUnit")?.doubleValue.nanToZero ?? 0, 10_000)),
                lowInMeals: (o["lowInMeals"] as? Bool) ?? false,
                noRebuy: (o["noRebuy"] as? Bool) ?? false
            ))
        }
        let refs = Set(items.map(\.id))
        var events: [Event] = []
        for case let o as [String: Any] in root["events"] as? [Any] ?? [] {
            guard let ref = number(o, "item")?.int64Value, refs.contains(ref),
                  let day = number(o, "epochDay")?.intValue,
                  let quantity = number(o, "quantity")?.doubleValue, !quantity.isNaN, quantity != 0 else { continue }
            let type = EventType.from(string(o, "type"))
            if type != .ADJUST && quantity < 0 { continue }
            let price = number(o, "priceMinor")?.int64Value
            events.append(Event(
                itemId: ref,
                type: type.rawValue,
                epochDay: day,
                quantity: quantity,
                priceMinor: type == .BOUGHT && (price ?? -1) >= 0 ? price : nil,
                shop: String(string(o, "shop").trimmingCharacters(in: .whitespaces).prefix(40)),
                receiptFile: includePhotos ? string(o, "receiptFile") : "",
                note: String(string(o, "note").prefix(200)),
                createdAt: number(o, "createdAt")?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000),
                countsFromDay: type == .BOUGHT ? number(o, "countsFromDay")?.intValue : nil,
                craving: type == .BOUGHT && ((o["craving"] as? Bool) ?? false)
            ))
        }
        var notes: [ShoppingNote] = []
        for case let o as [String: Any] in root["shopping"] as? [Any] ?? [] {
            let text = string(o, "text").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                notes.append(ShoppingNote(text: String(text.prefix(80)), createdAt: number(o, "createdAt")?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000)))
            }
        }
        return (items, events, notes)
    }
}

private extension Double {
    var nanToZero: Double { isNaN ? 0 : self }
}

/// Photos are resized and kept in the app's private storage.
enum PhotoStore {
    static var directory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Kitchtally/photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func save(_ image: UIImage) -> String? {
        let maxSide: CGFloat = 1280
        let scale = min(1, maxSide / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let resized = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = resized.jpegData(compressionQuality: 0.85) else { return nil }
        let name = "\(UUID().uuidString).jpg"
        do {
            try data.write(to: directory.appendingPathComponent(name), options: [.atomic, .completeFileProtection])
            return name
        } catch {
            return nil
        }
    }

    static func load(_ name: String) -> UIImage? {
        name.isEmpty ? nil : UIImage(contentsOfFile: directory.appendingPathComponent(name).path)
    }

    private static let thumbs: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 24 * 1024 * 1024
        return cache
    }()

    /// A small cached copy for lists, so many photos stay fast.
    static func thumbnail(_ name: String, size: CGFloat) -> UIImage? {
        guard !name.isEmpty else { return nil }
        let key = "\(name)@\(Int(size))" as NSString
        if let cached = thumbs.object(forKey: key) { return cached }
        guard let image = load(name) else { return nil }
        let side = size * UIScreen.main.scale
        let scale = side / min(image.size.width, image.size.height)
        let target = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: target, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
        thumbs.setObject(small, forKey: key, cost: Int(target.width * target.height * 4))
        return small
    }

    static func delete(_ name: String) {
        if !name.isEmpty { try? FileManager.default.removeItem(at: directory.appendingPathComponent(name)) }
    }
}

/// Everything, saved on this iPhone only after every change.
@MainActor
final class Store: ObservableObject {
    @Published private(set) var items: [Item] = []
    @Published private(set) var events: [Event] = []
    @Published private(set) var notes: [ShoppingNote] = []
    @Published var problem: String?
    @Published var currency: String {
        didSet { UserDefaults.standard.set(currency, forKey: "currency") }
    }
    /// Money on the Weekly tab stays hidden until asked for.
    @Published var showSpending: Bool {
        didSet { UserDefaults.standard.set(showSpending, forKey: "show_spending") }
    }
    /// Monthly budget in cents; 0 means off.
    /// First day of the week: 1 = Monday ... 7 = Sunday.
    @Published var weekStartDay: Int {
        didSet {
            UserDefaults.standard.set(weekStartDay, forKey: "week_start_day")
            // Choosing a first day starts the weeks over from that day, and the old switch was worked out for the old week.
            if oldValue != weekStartDay { setWeekAnchor(0); setForNextWeek(false) }
        }
    }
    /// "Shopping for next week" stays on until this day (the next week's first day), then switches itself off. 0 = off.
    @Published private(set) var forNextWeekUntil: Int

    @Published var budgetMinor: Int64 {
        didSet { UserDefaults.standard.set(budgetMinor, forKey: "budget_minor") }
    }

    private let fileURL: URL
    private var nextEventId: Int64 = 1
    private var nextNoteId: Int64 = 1

    init(directory: URL? = nil) {
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Kitchtally", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("pantry.json")
        currency = UserDefaults.standard.string(forKey: "currency") ?? "EUR"
        showSpending = UserDefaults.standard.bool(forKey: "show_spending")
        budgetMinor = Int64(max(0, UserDefaults.standard.integer(forKey: "budget_minor")))
        let savedStart = UserDefaults.standard.integer(forKey: "week_start_day")
        weekStartDay = (1...7).contains(savedStart) ? savedStart : 1
        forNextWeekUntil = UserDefaults.standard.integer(forKey: "for_next_week_until")
        load()
    }

    // MARK: Reading

    func item(_ id: Int64) -> Item? { items.first { $0.id == id } }
    func history(_ id: Int64) -> [Event] {
        events.filter { $0.itemId == id }.sorted { $0.epochDay != $1.epochDay ? $0.epochDay > $1.epochDay : $0.createdAt > $1.createdAt }
    }
    func entries(_ id: Int64) -> [Entry] { events.filter { $0.itemId == id }.map(\.entry) }
    func stock(_ id: Int64) -> Double { Pantry.stock(entries(id)) }
    var entriesByItem: [Int64: [Entry]] { Dictionary(grouping: events, by: \.itemId).mapValues { $0.map(\.entry) } }
    var usedShops: [String] { events.sorted { $0.createdAt > $1.createdAt }.map(\.shop).filter { !$0.isEmpty } }
    var usedCategories: [String] { items.map(\.category) }

    func status(_ item: Item, _ today: Day) -> StockStatus { Pantry.status(entries(item.id), item.counting, today) }
    /// On the list when marked, out or running low, unless hidden until it's bought again.
    func onShoppingList(_ item: Item, _ today: Day) -> Bool {
        !item.unsure && !item.noRebuy && (item.refillNeeded || (!item.hiddenFromList && status(item, today) != .ok))
    }

    // MARK: Saving

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let contents = try Backup.decode(try Data(contentsOf: fileURL), includePhotos: true)
            items = contents.items
            events = contents.events.map { e in
                var copy = e
                copy.id = nextEventId
                nextEventId += 1
                return copy
            }
            notes = contents.notes.map { n in
                var copy = n
                copy.id = nextNoteId
                nextNoteId += 1
                return copy
            }
        } catch {
            let kept = fileURL.deletingLastPathComponent().appendingPathComponent("pantry-unreadable-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: fileURL, to: kept)
            problem = "Your saved data couldn't be read, so Kitchtally started empty. The old file was kept."
        }
    }

    private func persist() {
        do {
            try Backup.encode(items, events, notes: notes, includePhotos: true).write(to: fileURL, options: [.atomic, .completeFileProtection])
        } catch {
            problem = "Couldn't save: \(error.localizedDescription)"
        }
    }

    @discardableResult
    func saveItem(_ item: Item, startStock: Double = 0) -> Int64 {
        if item.id == 0 {
            var copy = item
            copy.id = (items.map(\.id).max() ?? 0) + 1
            items.append(copy)
            if startStock > 0 {
                appendEvent(Event(itemId: copy.id, type: EventType.ADJUST.rawValue, epochDay: Day.today().epochDay, quantity: startStock, note: "Starting stock"))
            }
            persist()
            return copy.id
        }
        if let old = self.item(item.id), old.photoFile != item.photoFile { PhotoStore.delete(old.photoFile) }
        items = items.map { $0.id == item.id ? item : $0 }
        persist()
        return item.id
    }

    func deleteItem(_ id: Int64) {
        if let i = self.item(id) { PhotoStore.delete(i.photoFile) }
        events.filter { $0.itemId == id }.forEach { PhotoStore.delete($0.receiptFile) }
        items.removeAll { $0.id == id }
        events.removeAll { $0.itemId == id }
        persist()
    }

    func setHidden(_ id: Int64, _ hidden: Bool) {
        items = items.map { i in
            var copy = i
            if i.id == id {
                copy.hiddenFromList = hidden
                if hidden { copy.refillNeeded = false }
            }
            return copy
        }
        persist()
    }

    /// Marking an item as needed also means you're sure about buying it again.
    func setRefillNeeded(_ id: Int64, _ needed: Bool) {
        items = items.map { i in
            var copy = i
            if i.id == id {
                copy.refillNeeded = needed
                if needed { copy.unsure = false; copy.noRebuy = false }
            }
            return copy
        }
        persist()
    }

    func setUnsure(_ id: Int64, _ unsure: Bool) {
        items = items.map { i in
            var copy = i
            if i.id == id {
                copy.unsure = unsure
                if unsure { copy.refillNeeded = false; copy.noRebuy = false }
            }
            return copy
        }
        persist()
    }

    func setNoRebuy(_ id: Int64, _ on: Bool) {
        items = items.map { i in
            var copy = i
            if i.id == id {
                copy.noRebuy = on
                if on { copy.refillNeeded = false; copy.unsure = false }
            }
            return copy
        }
        persist()
    }

    private func appendEvent(_ e: Event) {
        var copy = e
        copy.id = nextEventId
        nextEventId += 1
        events.append(copy)
    }

    /// The day "Start new week" was pressed (epoch day), or 0. Weeks run seven days from it.
    @Published private(set) var weekAnchorDay: Int = UserDefaults.standard.integer(forKey: "week_anchor_day")

    var weekAnchor: Day? { weekAnchorDay > 0 ? Day(epochDay: weekAnchorDay) : nil }

    func setWeekAnchor(_ day: Int) {
        weekAnchorDay = day
        UserDefaults.standard.set(day, forKey: "week_anchor_day")
    }

    /// Today becomes day one of a new week. Returns the old anchor, for Undo.
    @discardableResult
    func startNewWeek(_ today: Day) -> Int {
        let before = weekAnchorDay
        setWeekAnchor(today.epochDay)
        return before
    }

    /// Face cream, toilet paper, shower gel and the like are counted in Budget, not in the weekly shop.
    func isLong(_ id: Int64) -> Bool { item(id).map { Categories.isLongLasting($0.category) } ?? false }

    func isForNextWeek(_ today: Day) -> Bool { forNextWeekUntil > today.epochDay }

    func setForNextWeek(_ on: Bool) {
        let until = on ? Pantry.weekStart(Day.today(), first: weekStartDay).plusDays(7).epochDay : 0
        forNextWeekUntil = until
        UserDefaults.standard.set(until, forKey: "for_next_week_until")
    }

    /// Logs a quick change today. Returns its id for Undo.
    @discardableResult
    func log(_ itemId: Int64, _ type: EventType, quantity: Double = 1, priceMinor: Int64? = nil, shop: String = "") -> Int64 {
        let today = Day.today()
        let countsFrom: Int? = (type == .BOUGHT && isForNextWeek(today)) ? forNextWeekUntil : nil
        appendEvent(Event(itemId: itemId, type: type.rawValue, epochDay: today.epochDay, quantity: quantity, priceMinor: priceMinor, shop: shop, countsFromDay: countsFrom))
        if type == .BOUGHT { clearRefill(itemId) }
        persist()
        return nextEventId - 1
    }

    /// − or Shared: the item's step, never more than is left. Returns the event id and amount, or nil at zero.
    func use(_ item: Item, shared: Bool) -> (Int64, Double)? {
        let amount = Pantry.useAmount(item.step, stock(item.id))
        guard amount > 0 else { return nil }
        return (log(item.id, shared ? .SHARED : .USED, quantity: amount), amount)
    }

    /// The quick ＋: one more (or the usual pack size for kilos and litres), today, at last time's shop and price.
    func quickBuy(_ item: Item) -> QuickBuy {
        let list = entries(item.id)
        let amount = Pantry.buyAmount(item.unitValue, list, packSize: item.packSize)
        let price = Pantry.expectedPriceMinor(item.unitValue, list, packSize: item.packSize)
        let last = Pantry.lastPriced(list)
        let shop = last?.shop.isEmpty == false ? last!.shop : (Pantry.lastBought(list)?.shop ?? "")
        let id = log(item.id, .BOUGHT, quantity: amount, priceMinor: price, shop: shop)
        return QuickBuy(eventId: id, amount: amount, itemId: item.id, wasRefill: item.refillNeeded, wasHidden: item.hiddenFromList)
    }

    enum Adjust { case used, shared, bought, set }

    /// The adjust box. Returns a message and an Undo action, or nil if nothing changed.
    func adjust(_ item: Item, _ action: Adjust, _ amount: Double) -> (String, () -> Void)? {
        let unit = item.unitValue
        let current = stock(item.id)
        switch action {
        case .used, .shared:
            let q = Qty.round(min(amount, current))
            guard q > 0 else { return nil }
            let id = log(item.id, action == .shared ? .SHARED : .USED, quantity: q)
            return ("\(action == .shared ? "Shared" : "Used") \(unit.label(q))", { self.deleteEvent(id) })
        case .bought:
            guard amount > 0 else { return nil }
            let list = entries(item.id)
            let last = Pantry.lastPriced(list)
            let shop = last?.shop.isEmpty == false ? last!.shop : (Pantry.lastBought(list)?.shop ?? "")
            let id = log(item.id, .BOUGHT, quantity: amount, priceMinor: Pantry.priceFor(list, amount), shop: shop)
            let buy = QuickBuy(eventId: id, amount: amount, itemId: item.id, wasRefill: item.refillNeeded, wasHidden: item.hiddenFromList)
            return ("Added \(unit.label(amount))", { self.undoBuy(buy) })
        case .set:
            let diff = Qty.round(amount - current)
            guard diff != 0 else { return nil }
            appendEvent(Event(itemId: item.id, type: EventType.ADJUST.rawValue, epochDay: Day.today().epochDay, quantity: diff, note: "Counted"))
            let id = nextEventId - 1
            // Topping the stock up means it no longer needs buying.
            let restocked = diff > 0 && item.refillNeeded
            if restocked { clearRefill(item.id) }
            persist()
            return ("Set to \(unit.label(amount))", {
                self.deleteEvent(id)
                if restocked { self.items = self.items.map { i in var c = i; if i.id == item.id { c.refillNeeded = true }; return c }; self.persist() }
            })
        }
    }

    func undoBuy(_ buy: QuickBuy) {
        events.removeAll { $0.id == buy.eventId }
        items = items.map { i in
            var copy = i
            if i.id == buy.itemId {
                copy.refillNeeded = buy.wasRefill
                copy.hiddenFromList = buy.wasHidden
            }
            return copy
        }
        persist()
    }

    /// After buying: off the manual list, and shown again if it was hidden.
    private func clearRefill(_ id: Int64) {
        items = items.map { i in
            var copy = i
            if i.id == id {
                copy.refillNeeded = false
                copy.hiddenFromList = false
            }
            return copy
        }
    }

    /// Adds a line to the shopping list, or marks a pantry item with that name. Returns true if it matched an item.
    @discardableResult
    func addNote(_ text: String) -> Bool {
        let clean = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !clean.isEmpty else { return false }
        if let match = items.first(where: { $0.name.lowercased() == clean.lowercased() }) {
            items = items.map { i in
                var copy = i
                if i.id == match.id {
                    copy.refillNeeded = true
                    copy.hiddenFromList = false
                    copy.unsure = false
                    copy.noRebuy = false
                }
                return copy
            }
            persist()
            return true
        }
        notes.append(ShoppingNote(id: nextNoteId, text: clean))
        nextNoteId += 1
        persist()
        return false
    }

    func deleteNote(_ id: Int64) {
        notes.removeAll { $0.id == id }
        persist()
    }

    func restoreNote(_ note: ShoppingNote) {
        var copy = note
        copy.id = nextNoteId
        nextNoteId += 1
        notes.append(copy)
        notes.sort { $0.createdAt < $1.createdAt }
        persist()
    }

    func saveEvent(_ e: Event) {
        if e.id == 0 {
            appendEvent(e)
        } else {
            if let old = events.first(where: { $0.id == e.id }), old.receiptFile != e.receiptFile { PhotoStore.delete(old.receiptFile) }
            events = events.map { $0.id == e.id ? e : $0 }
        }
        if e.typeValue == .BOUGHT { clearRefill(e.itemId) }
        persist()
    }

    func deleteEvent(_ id: Int64) {
        if let e = events.first(where: { $0.id == id }) { PhotoStore.delete(e.receiptFile) }
        events.removeAll { $0.id == id }
        persist()
    }

    /// Cook: one meal from each item (or one tap for things like oil), logged and undone together.
    func cook(_ chosen: [Item], shared: Bool) -> (String, () -> Void)? {
        var ids: [Int64] = []
        for item in chosen {
            let amount = Pantry.useAmount(Meals.cookAmount(item.step, item.countMeals ? item.mealsPerUnit : 0), stock(item.id))
            guard amount > 0 else { continue }
            appendEvent(Event(itemId: item.id, type: (shared ? EventType.SHARED : .USED).rawValue, epochDay: Day.today().epochDay, quantity: amount, note: "Cooked"))
            ids.append(nextEventId - 1)
        }
        guard !ids.isEmpty else { return nil }
        persist()
        let gone = Set(ids)
        return ("Cooked with \(chosen.map(\.name).joined(separator: ", "))", {
            self.events.removeAll { gone.contains($0.id) }
            self.persist()
        })
    }

    /// Done shopping: everything ticked bought at once, at last time's amount, shop and price.
    func doneShopping(_ chosen: [Item]) -> (String, () -> Void)? {
        guard !chosen.isEmpty else { return nil }
        let buys = chosen.map { quickBuy($0) }
        return ("Bought \(buys.count) item\(buys.count == 1 ? "" : "s")", { buys.forEach { self.undoBuy($0) } })
    }

    /// From the purchase screen: "these make about 8 meals".
    func setMealsPerUnit(_ id: Int64, _ mealsPerUnit: Double) {
        items = items.map { i in
            var copy = i
            if i.id == id {
                copy.countMeals = true
                copy.mealsPerUnit = mealsPerUnit
            }
            return copy
        }
        persist()
    }

    // MARK: Backup

    func exportFile() throws -> URL {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("kitchtally-backup-\(f.string(from: Date())).json")
        try Backup.encode(items, events, notes: notes).write(to: url, options: .atomic)
        return url
    }

    func importData(_ data: Data) throws -> Int {
        let contents = try Backup.decode(data)
        var nextItem = (items.map(\.id).max() ?? 0) + 1
        for item in contents.items {
            var copy = item
            copy.id = nextItem
            items.append(copy)
            for e in contents.events where e.itemId == item.id {
                var ev = e
                ev.itemId = nextItem
                appendEvent(ev)
            }
            nextItem += 1
        }
        for n in contents.notes {
            var copy = n
            copy.id = nextNoteId
            nextNoteId += 1
            notes.append(copy)
        }
        persist()
        return contents.items.count
    }
}
