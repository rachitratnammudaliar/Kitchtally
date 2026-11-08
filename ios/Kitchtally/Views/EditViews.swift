import SwiftUI

/// % items are typed as percentages (25) but stored as a fraction of one (0.25).
private func toDisplay(_ unit: QtyUnit, _ value: Double) -> String { unit.isLevel ? "\(Qty.percent(value))" : Qty.format(value) }
private func fromDisplay(_ unit: QtyUnit, _ value: Double) -> Double { unit.isLevel ? Qty.round(value / 100) : value }

struct EditItemView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let existing: Item?

    @State private var name: String
    @State private var category: String
    @State private var unit: QtyUnit
    @State private var life: Life
    @State private var lowAt: String
    @State private var stepText: String
    @State private var packText: String
    @State private var stockText: String
    @State private var refill: Bool
    @State private var unsure: Bool
    @State private var countMeals: Bool
    @State private var mealsText: String
    @State private var lowInMeals: Bool
    @State private var notes: String
    @State private var photo: String
    @State private var showErrors = false
    // A new item fills in from its name until you choose these yourself.
    @State private var categoryTouched = false
    @State private var unitTouched = false
    @State private var lifeTouched = false
    @State private var packTouched = false
    @State private var mealsTouched = false
    @State private var lowTouched = false
    private let originalPhoto: String

    private func applySuggestion() {
        guard existing == nil else { return }
        let guess = Suggestions.forName(name)
        if !categoryTouched { category = guess?.category ?? "Other" }
        if !unitTouched { changeUnit(guess?.unit ?? .PACKET) }
        if !lifeTouched { life = guess?.life ?? .MEDIUM }
        if !packTouched { packText = (guess?.packSize ?? 0) > 0 ? Qty.format(guess!.packSize) : "" }
        if !mealsTouched { countMeals = guess?.countMeals ?? false }
    }

    /// Switching between % and other units converts what's typed; "running low at" follows unless set by hand.
    private func changeUnit(_ next: QtyUnit) {
        let old = unit
        if old.isLevel != next.isLevel {
            if let v = Double(stockText.replacingOccurrences(of: ",", with: ".")) { stockText = toDisplay(next, fromDisplay(old, v)) }
            stepText = ""
        }
        if !(lowInMeals && countMeals) && !(lowTouched && old.isLevel == next.isLevel) {
            lowAt = toDisplay(next, Pantry.defaultLowAt(next))
        }
        unit = next
    }

    init(existing: Item?) {
        self.existing = existing
        let u = existing?.unitValue ?? .PACKET
        _name = State(initialValue: existing?.name ?? "")
        _category = State(initialValue: existing?.category ?? "Other")
        _unit = State(initialValue: u)
        _life = State(initialValue: existing?.lifeValue ?? .MEDIUM)
        if let e = existing {
            _lowAt = State(initialValue: e.lowInMeals ? Qty.format(e.lowAt) : toDisplay(u, e.lowAt))
        } else {
            _lowAt = State(initialValue: toDisplay(u, Pantry.defaultLowAt(u)))
        }
        _stepText = State(initialValue: existing.map { toDisplay(u, $0.step) } ?? "")
        _packText = State(initialValue: (existing?.packSize ?? 0) > 0 ? Qty.format(existing!.packSize) : "")
        _stockText = State(initialValue: "")
        _refill = State(initialValue: existing?.refillNeeded ?? false)
        _unsure = State(initialValue: existing?.unsure ?? false)
        _countMeals = State(initialValue: existing?.countMeals ?? false)
        let pack = (existing?.packSize ?? 0) > 0 ? existing!.packSize : 1
        _mealsText = State(initialValue: (existing?.mealsPerUnit ?? 0) > 0 ? Qty.format(Qty.round(existing!.mealsPerUnit * pack)) : "")
        _lowInMeals = State(initialValue: existing?.lowInMeals ?? false)
        _notes = State(initialValue: existing?.notes ?? "")
        _photo = State(initialValue: existing?.photoFile ?? "")
        originalPhoto = existing?.photoFile ?? ""
        _categoryTouched = State(initialValue: existing != nil)
        _unitTouched = State(initialValue: existing != nil)
        _lifeTouched = State(initialValue: existing != nil)
        _packTouched = State(initialValue: existing != nil)
        _mealsTouched = State(initialValue: existing != nil)
        _lowTouched = State(initialValue: existing != nil)
    }

    private func number(_ text: String) -> Double? {
        if text.trimmingCharacters(in: .whitespaces).isEmpty { return 0 }
        guard let v = Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")), v >= 0, v <= 10_000 else { return nil }
        return Qty.round(v)
    }

    private var lowInMealsNow: Bool { lowInMeals && countMeals }
    private var nameError: Bool { name.trimmingCharacters(in: .whitespaces).isEmpty }
    private var lowValue: Double? { number(lowAt).map { lowInMealsNow ? $0 : fromDisplay(unit, $0) } }
    private var stockValue: Double? { number(stockText).map { fromDisplay(unit, $0) } }
    private var stepValue: Double? {
        stepText.trimmingCharacters(in: .whitespaces).isEmpty ? Pantry.defaultStep(unit) : Qty.parse(stepText).map { fromDisplay(unit, $0) }
    }
    private var duplicate: Bool {
        let n = name.trimmingCharacters(in: .whitespaces).lowercased()
        return !n.isEmpty && store.items.contains { $0.id != existing?.id && $0.name.trimmingCharacters(in: .whitespaces).lowercased() == n }
    }
    private var packValue: Double? { packText.trimmingCharacters(in: .whitespaces).isEmpty || unit.isLevel ? 0 : Qty.parse(packText) }
    private var mealsValue: Double? { !countMeals || mealsText.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : Qty.parse(mealsText) }
    private var invalid: Bool { nameError || lowValue == nil || stockValue == nil || stepValue == nil || packValue == nil || mealsValue == nil || category.isEmpty }
    private var inUnit: String { unit.isLevel ? "%" : unit.plural }

    private func save() {
        if invalid {
            showErrors = true
            return
        }
        var item = existing ?? Item(name: "")
        item.name = String(name.trimmingCharacters(in: .whitespaces).prefix(60))
        item.category = category
        item.unit = unit.rawValue
        item.life = life.rawValue
        item.lowAt = lowValue ?? 1
        item.refillNeeded = refill && !unsure
        item.unsure = unsure
        item.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        item.photoFile = photo
        item.step = stepValue ?? 1
        item.packSize = packValue ?? 0
        item.countMeals = countMeals
        let meals = mealsValue ?? 0
        item.mealsPerUnit = meals > 0 ? Qty.round(meals / ((packValue ?? 0) > 0 ? packValue! : 1)) : 0
        item.lowInMeals = lowInMealsNow
        let target = stockValue ?? 0
        if let e = existing {
            // An empty "amount now" keeps the current amount.
            if !stockText.trimmingCharacters(in: .whitespaces).isEmpty {
                let diff = Qty.round(target - max(0, store.stock(e.id)))
                if diff != 0 { store.log(e.id, .ADJUST, quantity: diff) }
            }
            store.saveItem(item)
        } else {
            store.saveItem(item, startStock: target)
        }
        dismiss()
    }

    private func cancel() {
        if !photo.isEmpty && photo != originalPhoto { PhotoStore.delete(photo) }
        dismiss()
    }

    private func numberRow(_ title: String, _ placeholder: String, _ text: Binding<String>, _ suffix: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField(placeholder, text: text).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 80)
            Text(suffix).foregroundColor(.secondary)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 14) {
                        ItemImage(name: name, photoFile: photo, category: category, size: 64)
                        PhotoButtons(hasPhoto: !photo.isEmpty, onPicked: { newFile in
                            if !photo.isEmpty && photo != originalPhoto { PhotoStore.delete(photo) }
                            photo = newFile
                        }, onRemove: {
                            if photo != originalPhoto { PhotoStore.delete(photo) }
                            photo = ""
                        })
                    }
                    TextField("Name, e.g. Basmati rice", text: $name)
                        .onChange(of: name) { _ in applySuggestion() }
                    if showErrors && nameError { Text("Enter a name").font(.caption).foregroundColor(.red) }
                    if duplicate { Text("You already have an item called this. You can still save it.").font(.caption).foregroundColor(.orange) }
                }

                Section("Category") {
                    NamedChoice(options: uniqueCategories, selected: Binding(get: { category }, set: { category = $0; categoryTouched = true }), otherLabel: "Category name")
                    if showErrors && category.isEmpty { Text("Choose or type a category").font(.caption).foregroundColor(.red) }
                }

                Section {
                    Picker("Unit", selection: Binding(get: { unit }, set: { changeUnit($0); unitTouched = true })) {
                        ForEach(QtyUnit.allCases) { Text($0.title).tag($0) }
                    }
                } header: {
                    Text("Counted in")
                } footer: {
                    if unit.isLevel { Text("Shows how full it is: 100%, 75%, 50%, 25%. Good for bread, oil, sauces and opened packets.") }
                    else if unit.priceDivisor > 1 { Text("Prices are still shown \(unit.priceLabel).") }
                }

                Section {
                    Picker("Lasts", selection: Binding(get: { life }, set: { life = $0; lifeTouched = true })) {
                        ForEach(Life.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Text(life.description).font(.caption).foregroundColor(.secondary)
                } header: {
                    Text("How long it lasts")
                }

                Section {
                    numberRow(existing == nil ? "You have now" : "Amount now",
                              existing == nil ? "0" : toDisplay(unit, max(0, store.stock(existing!.id))), $stockText, inUnit)
                    if unit.isLevel {
                        Picker("How full", selection: Binding(get: { Int(stockText) ?? -1 }, set: { stockText = "\($0)" })) {
                            ForEach([100, 75, 50, 25, 0], id: \.self) { Text("\($0)%").tag($0) }
                        }
                        .pickerStyle(.segmented)
                    }
                    Toggle(isOn: Binding(get: { countMeals }, set: { on in
                        countMeals = on
                        mealsTouched = true
                        if !on && lowInMeals {
                            lowInMeals = false
                            lowAt = toDisplay(unit, Pantry.defaultLowAt(unit))
                        }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Count meals")
                            Text("Leave it off for salt, oil, spices and the like").font(.caption).foregroundColor(.secondary)
                        }
                    }
                    if countMeals {
                        numberRow((packValue ?? 0) > 0 ? "1 pack makes about" : unit.isLevel ? "A full one makes about" : "1 \(unit.singular) makes about", "e.g. 4", $mealsText, "meals")
                    }
                } footer: {
                    Text(countMeals ? "A packet of spaghetti might make 4 meals, one potato about half a meal (0.5). You can also fill it in when you buy." : (existing == nil ? "" : "Leave \"amount now\" empty to keep the current amount."))
                }

                Section {
                    if countMeals {
                        Picker("Count in", selection: Binding(get: { lowInMealsNow }, set: { inMeals in
                            guard inMeals != lowInMeals else { return }
                            lowInMeals = inMeals
                            lowAt = inMeals ? Qty.format(Pantry.defaultLowMeals) : toDisplay(unit, Pantry.defaultLowAt(unit))
                        })) {
                            Text("In meals").tag(true)
                            Text(unit.isLevel ? "In %" : "In \(unit.plural)").tag(false)
                        }
                        .pickerStyle(.segmented)
                    }
                    numberRow("Running low at", "1", Binding(get: { lowAt }, set: { lowAt = $0; lowTouched = true }), lowInMealsNow ? "meals" : inUnit)
                    numberRow("Each − or Shared uses", toDisplay(unit, Pantry.defaultStep(unit)), $stepText, inUnit)
                    if !unit.isLevel {
                        numberRow("Comes in packs of", "optional", Binding(get: { packText }, set: { packText = $0; packTouched = true }), unit.plural)
                    }
                    if showErrors && (lowValue == nil || stockValue == nil || stepValue == nil || packValue == nil || mealsValue == nil) {
                        Text("Use a number, 0 or more").font(.caption).foregroundColor(.red)
                    }
                    Toggle("Refill needed", isOn: Binding(get: { refill }, set: { refill = $0; if $0 { unsure = false } }))
                    Toggle("Not sure to buy again", isOn: Binding(get: { unsure }, set: { unsure = $0; if $0 { refill = false } }))
                } header: {
                    Text("Running low")
                } footer: {
                    Text("It says \"almost out\" and goes on the shopping list at or below \"running low at\"." + (countMeals ? " It turns red at 1 meal." : "") + (unit.isLevel ? " For oil, each use might be 5 or 10%." : " For eggs, count in pieces with packs of 10: ＋ adds a pack, − takes one egg."))
                }

                Section {
                    TextField("Notes, e.g. brand or size", text: $notes, axis: .vertical).lineLimit(2...6)
                }
            }
            .navigationTitle(existing == nil ? "New item" : "Edit item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: cancel) }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save) }
            }
        }
    }

    private var uniqueCategories: [String] {
        var seen = Set<String>()
        return (Categories.presets + store.usedCategories).filter { seen.insert($0.lowercased()).inserted }
    }
}

struct PurchaseView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let target: PurchaseTarget
    let today: Day

    @State private var loaded = false
    @State private var qtyText = "1"
    @State private var inPacks = true
    @State private var date = Date()
    @State private var nextWeek = false
    @State private var perUnit = false
    @State private var priceText = ""
    @State private var shop = ""
    @State private var receipt = ""
    @State private var originalReceipt = ""
    @State private var note = ""
    @State private var mealsText = ""
    @State private var showErrors = false

    private var existing: Event? { target.eventId == 0 ? nil : store.events.first { $0.id == target.eventId } }
    private var packSize: Double { store.item(target.itemId)?.packSize ?? 0 }
    private var packMode: Bool { packSize > 0 && inPacks }
    private var entered: Double? { Qty.parse(qtyText) }
    /// Always in the item's unit, whatever was typed.
    private var qty: Double? { entered.map { packMode ? Qty.round($0 * packSize) : $0 } }
    private var price: Int64? { priceText.isEmpty ? nil : Money.parseToMinor(priceText) }
    private var priceError: Bool { !priceText.isEmpty && price == nil }
    private var unit: QtyUnit { store.item(target.itemId)?.unitValue ?? .PIECE }
    private var total: Int64? {
        guard let p = price, let q = qty else { return nil }
        if !perUnit { return p }
        return packMode ? Pantry.totalMinor(p, entered ?? q) : Pantry.totalMinor(p, q, unit)
    }
    private var mealsValue: Double? { mealsText.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : Qty.parse(mealsText) }
    /// What this costs per unit (per kg for grams), for the price check.
    private var myUnitPrice: Int64? {
        guard let t = total, t > 0, let q = qty else { return nil }
        return Pantry.unitPriceMinor(Entry(type: .BOUGHT, date: today, quantity: q, priceMinor: t), unit)
    }

    private func load() {
        guard !loaded, let item = store.item(target.itemId) else { return }
        loaded = true
        if let e = existing {
            inPacks = false
            qtyText = Qty.format(e.quantity)
            date = e.date.localDate
            nextWeek = e.countsFromDay != nil
            priceText = e.priceMinor.map { Money.minorToInput($0) } ?? ""
            shop = e.shop
            receipt = e.receiptFile
            originalReceipt = e.receiptFile
            note = e.note
        } else {
            nextWeek = store.isForNextWeek(today)
            let entries = store.entries(target.itemId)
            let last = Pantry.lastPriced(entries)
            shop = (last?.shop.isEmpty == false ? last?.shop : Pantry.lastBought(entries)?.shop) ?? ""
            if item.packSize > 0 {
                inPacks = true
                if let p = Pantry.priceFor(entries, item.packSize) { priceText = Money.minorToInput(p) }
            } else {
                if !item.unitValue.isLevel { qtyText = Qty.format(Pantry.buyAmount(item.unitValue, entries)) }
                if let last = last, let unitPrice = Pantry.unitPriceMinor(last, item.unitValue) {
                    // Kilos, grams and litres are priced per kg or litre.
                    perUnit = item.unitValue.byWeight
                    priceText = Money.minorToInput(perUnit ? unitPrice : (Pantry.priceFor(entries, Qty.parse(qtyText) ?? 1) ?? unitPrice))
                }
            }
        }
    }

    private func save() {
        guard let q = qty, !priceError, let meals = mealsValue else {
            showErrors = true
            return
        }
        if meals > 0, let per = Meals.perUnit(q, meals) { store.setMealsPerUnit(target.itemId, per) }
        store.saveEvent(Event(
            id: existing?.id ?? 0,
            itemId: target.itemId,
            type: EventType.BOUGHT.rawValue,
            epochDay: Day(localDate: date).epochDay,
            quantity: q,
            priceMinor: total,
            shop: shop.trimmingCharacters(in: .whitespaces),
            receiptFile: receipt,
            note: note.trimmingCharacters(in: .whitespaces),
            createdAt: existing?.createdAt ?? Int64(Date().timeIntervalSince1970 * 1000),
            countsFromDay: nextWeek ? Pantry.weekStart(Day(localDate: date), first: store.weekStartDay).plusDays(7).epochDay : nil
        ))
        dismiss()
    }

    private func cancel() {
        if !receipt.isEmpty && receipt != originalReceipt { PhotoStore.delete(receipt) }
        dismiss()
    }

    var body: some View {
        let item = store.item(target.itemId)
        NavigationStack {
            Form {
                Section {
                    if packSize > 0 {
                        Picker("Count in", selection: $inPacks) {
                            Text("Packs of \(Qty.format(packSize))").tag(true)
                            Text(unit.plural.capitalized).tag(false)
                        }
                        .pickerStyle(.segmented)
                    }
                    HStack {
                        Text(packMode ? "How many packs" : unit.byWeight ? "How much (\(unit.plural))" : "How many \(unit.plural)")
                        Spacer()
                        TextField("1", text: $qtyText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                    }
                    if showErrors && qty == nil { Text("Enter an amount above 0").font(.caption).foregroundColor(.red) }
                    if packMode, let q = qty { Text("= \(unit.label(q))").font(.caption).foregroundColor(.secondary) }
                    DatePicker("Bought on", selection: $date, in: ...Date(), displayedComponents: .date)
                    Picker("Counts in", selection: $nextWeek) {
                        Text("This week").tag(false)
                        Text("Next week").tag(true)
                    }
                    .pickerStyle(.segmented)
                    Text("A shop for next week stays in your pantry now, but shows in next week's summary and spending.")
                        .font(.caption).foregroundColor(.secondary)
                }

                Section("Price (optional)") {
                    Picker("Price is", selection: $perUnit) {
                        Text("Total paid").tag(false)
                        Text("Price \(packMode ? "per pack" : unit.priceLabel)").tag(true)
                    }
                    .pickerStyle(.segmented)
                    HStack {
                        Text(perUnit ? "\(store.currency) \(packMode ? "per pack" : unit.priceLabel)" : "Total in \(store.currency)")
                        Spacer()
                        TextField("0.00", text: $priceText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 110)
                    }
                    if showErrors && priceError { Text("Enter a price, up to 2 decimals").font(.caption).foregroundColor(.red) }
                    if perUnit, let t = total, let e = entered, let p = price {
                        Text("\(packMode ? Qty.format(e) : unit.label(qty ?? e)) × \(Money.format(p, store.currency)) = \(Money.format(t, store.currency))").font(.caption).foregroundColor(.secondary)
                    } else if !perUnit, let q = qty, q != 1 || unit.priceDivisor > 1, let up = myUnitPrice {
                        Text("\(Money.format(up, store.currency)) \(unit.priceLabel)").font(.caption).foregroundColor(.secondary)
                    }
                    if let up = myUnitPrice, let cheaper = Pantry.cheaperElsewhere(store.entries(target.itemId), unit, up, shop: shop, today) {
                        Text("💡 Cheaper at \(cheaper.shop) last time: \(Money.format(cheaper.unitPriceMinor, store.currency)) \(unit.priceLabel)")
                            .font(.caption).foregroundColor(.orange)
                    }
                }

                if let item = item, item.countMeals {
                    let guess = item.mealsPerUnit > 0 ? qty.map { Meals.left($0, item.mealsPerUnit) } : nil
                    Section {
                        HStack {
                            Text("These make about")
                            Spacer()
                            TextField(guess.map { Qty.approx($0) } ?? "e.g. 4", text: $mealsText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 80)
                            Text("meals").foregroundColor(.secondary)
                        }
                        if showErrors && mealsValue == nil { Text("A number above 0, or leave it empty").font(.caption).foregroundColor(.red) }
                    } header: {
                        Text("Meals (optional)")
                    } footer: {
                        Text(guess.map { "Leave it empty to keep about \(Qty.approx($0))." } ?? "Look at what you bought and guess. You can change it any time.")
                    }
                }

                Section("Shop") {
                    NamedChoice(options: Shops.options(store.usedShops), selected: $shop, otherLabel: "Shop name")
                }

                Section("Receipt photo (optional)") {
                    HStack(spacing: 12) {
                        if !receipt.isEmpty { ItemImage(name: "R", photoFile: receipt, size: 52) }
                        PhotoButtons(hasPhoto: !receipt.isEmpty, onPicked: { newFile in
                            if !receipt.isEmpty && receipt != originalReceipt { PhotoStore.delete(receipt) }
                            receipt = newFile
                        }, onRemove: {
                            if receipt != originalReceipt { PhotoStore.delete(receipt) }
                            receipt = ""
                        })
                    }
                }

                Section {
                    TextField("Note, e.g. on offer", text: $note)
                } footer: {
                    Text("Saving adds \(qty.map { unit.label($0) } ?? "it") to your stock" + ((item?.refillNeeded ?? false) ? " and takes it off the shopping list." : "."))
                }
            }
            .navigationTitle(existing == nil ? "Bought \(item?.name ?? "")" : "Edit purchase")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: cancel) }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save) }
            }
            .onAppear(perform: load)
        }
    }
}
