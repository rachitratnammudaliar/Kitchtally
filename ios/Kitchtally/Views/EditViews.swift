import SwiftUI

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
    @State private var notes: String
    @State private var photo: String
    @State private var showErrors = false
    private let originalPhoto: String

    init(existing: Item?) {
        self.existing = existing
        _name = State(initialValue: existing?.name ?? "")
        _category = State(initialValue: existing?.category ?? "Groceries")
        _unit = State(initialValue: existing?.unitValue ?? .PACKET)
        _life = State(initialValue: existing?.lifeValue ?? .MEDIUM)
        _lowAt = State(initialValue: Qty.format(existing?.lowAt ?? 1))
        _stepText = State(initialValue: existing.map { Qty.format($0.step) } ?? "")
        _packText = State(initialValue: (existing?.packSize ?? 0) > 0 ? Qty.format(existing!.packSize) : "")
        _stockText = State(initialValue: "")
        _refill = State(initialValue: existing?.refillNeeded ?? false)
        _notes = State(initialValue: existing?.notes ?? "")
        _photo = State(initialValue: existing?.photoFile ?? "")
        originalPhoto = existing?.photoFile ?? ""
    }

    private func number(_ text: String) -> Double? {
        if text.trimmingCharacters(in: .whitespaces).isEmpty { return 0 }
        guard let v = Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")), v >= 0, v <= 10_000 else { return nil }
        return Qty.round(v)
    }

    private var nameError: Bool { name.trimmingCharacters(in: .whitespaces).isEmpty }
    private var stepValue: Double? { stepText.trimmingCharacters(in: .whitespaces).isEmpty ? Pantry.defaultStep(unit) : Qty.parse(stepText) }
    private var duplicate: Bool {
        let n = name.trimmingCharacters(in: .whitespaces).lowercased()
        return !n.isEmpty && store.items.contains { $0.id != existing?.id && $0.name.trimmingCharacters(in: .whitespaces).lowercased() == n }
    }
    private var packValue: Double? { packText.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : Qty.parse(packText) }
    private var invalid: Bool { nameError || number(lowAt) == nil || number(stockText) == nil || stepValue == nil || packValue == nil || category.isEmpty }

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
        item.lowAt = number(lowAt) ?? 1
        item.refillNeeded = refill
        item.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        item.photoFile = photo
        item.step = stepValue ?? 1
        item.packSize = packValue ?? 0
        let target = number(stockText) ?? 0
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
                    if showErrors && nameError { Text("Enter a name").font(.caption).foregroundColor(.red) }
                    if duplicate { Text("You already have an item called this. You can still save it.").font(.caption).foregroundColor(.orange) }
                }

                Section("Category") {
                    NamedChoice(options: uniqueCategories, selected: $category, otherLabel: "Category name")
                    if showErrors && category.isEmpty { Text("Choose or type a category").font(.caption).foregroundColor(.red) }
                }

                Section("Counted in") {
                    Picker("Unit", selection: $unit) {
                        ForEach(QtyUnit.allCases) { Text($0.plural.capitalized).tag($0) }
                    }
                }

                Section {
                    Picker("Lasts", selection: $life) {
                        ForEach(Life.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Text(life.description).font(.caption).foregroundColor(.secondary)
                } header: {
                    Text("How long it lasts")
                }

                Section {
                    HStack {
                        Text(existing == nil ? "You have now" : "Amount now")
                        Spacer()
                        TextField(existing == nil ? "0" : Qty.format(max(0, store.stock(existing!.id))), text: $stockText)
                            .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                        Text(unit.plural).foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Running low at")
                        Spacer()
                        TextField("1", text: $lowAt).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                        Text(unit.plural).foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Each − or Shared uses")
                        Spacer()
                        TextField(Qty.format(Pantry.defaultStep(unit)), text: $stepText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                        Text(unit.plural).foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Comes in packs of")
                        Spacer()
                        TextField("optional", text: $packText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                        Text(unit.plural).foregroundColor(.secondary)
                    }
                    if showErrors && (number(lowAt) == nil || number(stockText) == nil || stepValue == nil || packValue == nil) {
                        Text("Use a number, 0 or more").font(.caption).foregroundColor(.red)
                    }
                    Toggle("Refill needed", isOn: $refill)
                } footer: {
                    Text("It goes on the shopping list at or below \"running low at\". \"Each − uses\" is, for example, 0.5 kg of rice per meal. For eggs, count in pieces with packs of 10: ＋ adds a pack, − takes one egg." + (existing == nil ? "" : " Leave \"amount now\" empty to keep the current amount."))
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
    @State private var perUnit = false
    @State private var priceText = ""
    @State private var shop = ""
    @State private var receipt = ""
    @State private var originalReceipt = ""
    @State private var note = ""
    @State private var showErrors = false

    private var existing: Event? { target.eventId == 0 ? nil : store.events.first { $0.id == target.eventId } }
    private var packSize: Double { store.item(target.itemId)?.packSize ?? 0 }
    private var packMode: Bool { packSize > 0 && inPacks }
    private var entered: Double? { Qty.parse(qtyText) }
    /// Always in the item's unit, whatever was typed.
    private var qty: Double? { entered.map { packMode ? Qty.round($0 * packSize) : $0 } }
    private var price: Int64? { priceText.isEmpty ? nil : Money.parseToMinor(priceText) }
    private var priceError: Bool { !priceText.isEmpty && price == nil }
    private var total: Int64? {
        guard let p = price, let q = qty else { return nil }
        return perUnit ? Pantry.totalMinor(p, entered ?? q) : p
    }

    private func load() {
        guard !loaded, let item = store.item(target.itemId) else { return }
        loaded = true
        if let e = existing {
            inPacks = false
            qtyText = Qty.format(e.quantity)
            date = e.date.localDate
            priceText = e.priceMinor.map { Money.minorToInput($0) } ?? ""
            shop = e.shop
            receipt = e.receiptFile
            originalReceipt = e.receiptFile
            note = e.note
        } else {
            let entries = store.entries(target.itemId)
            let last = Pantry.lastPriced(entries)
            shop = (last?.shop.isEmpty == false ? last?.shop : Pantry.lastBought(entries)?.shop) ?? ""
            if item.packSize > 0 {
                inPacks = true
                if let p = Pantry.priceFor(entries, item.packSize) { priceText = Money.minorToInput(p) }
            } else if let last = last, let unitPrice = Pantry.unitPriceMinor(last) {
                perUnit = item.unitValue == .KG || item.unitValue == .LITRE
                priceText = Money.minorToInput(unitPrice)
            }
        }
    }

    private func save() {
        guard let q = qty, !priceError else {
            showErrors = true
            return
        }
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
            createdAt: existing?.createdAt ?? Int64(Date().timeIntervalSince1970 * 1000)
        ))
        dismiss()
    }

    private func cancel() {
        if !receipt.isEmpty && receipt != originalReceipt { PhotoStore.delete(receipt) }
        dismiss()
    }

    var body: some View {
        let item = store.item(target.itemId)
        let unit = item?.unitValue ?? .PIECE
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
                        Text(packMode ? "How many packs" : "How many \(unit.plural)")
                        Spacer()
                        TextField("1", text: $qtyText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                    }
                    if showErrors && qty == nil { Text("Enter an amount above 0").font(.caption).foregroundColor(.red) }
                    if packMode, let q = qty { Text("= \(unit.label(q))").font(.caption).foregroundColor(.secondary) }
                    DatePicker("Bought on", selection: $date, in: ...Date(), displayedComponents: .date)
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
                        Text("\(Qty.format(e)) × \(Money.format(p, store.currency)) = \(Money.format(t, store.currency))").font(.caption).foregroundColor(.secondary)
                    } else if !perUnit, let t = total, let q = qty, q != 1 {
                        Text("\(Money.format(Pantry.totalMinor(t, 1 / q), store.currency)) \(unit.priceLabel)").font(.caption).foregroundColor(.secondary)
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
