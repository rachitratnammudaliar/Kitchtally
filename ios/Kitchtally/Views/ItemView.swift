import SwiftUI

struct ItemView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let id: Int64
    let today: Day

    @State private var editing = false
    @State private var buying: PurchaseTarget?
    @State private var confirmDelete = false
    @State private var openEvent: Event?
    @State private var receipt: String?
    @State private var undo: UndoState?


    var body: some View {
        if let item = store.item(id) {
            content(item)
        } else {
            Text("This item was deleted.").foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private func content(_ item: Item) -> some View {
        let entries = store.entries(id)
        let history = store.history(id)
        let unit = item.unitValue
        let stock = Pantry.stock(entries)
        let status = store.status(item, today)
        let lastPriced = Pantry.lastPriced(entries)

        List {
            Section {
                if let photo = PhotoStore.load(item.photoFile) {
                    Image(uiImage: photo).resizable().scaledToFill()
                        .frame(maxWidth: .infinity).frame(height: 200).clipped()
                        .listRowInsets(EdgeInsets())
                }
                HStack(spacing: 14) {
                    if item.photoFile.isEmpty || PhotoStore.load(item.photoFile) == nil {
                        ItemImage(name: item.name, category: item.category, size: 68)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.name).font(.title2.weight(.semibold))
                        StockPill(text: stockLabel(item, stock), status: status)
                        Text(detailLine(item, store, today)).font(.subheadline).foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 4)
                HStack(spacing: 8) {
                    Button {
                        if let r = store.use(item, shared: false) {
                            showUndo($undo, "Used \(unit.label(r.1))") { store.deleteEvent(r.0) }
                        }
                    } label: { Label("Used", systemImage: "minus").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).disabled(stock <= 0)
                    Button {
                        if let r = store.use(item, shared: true) {
                            showUndo($undo, "Shared \(unit.label(r.1))") { store.deleteEvent(r.0) }
                        }
                    } label: { Label("Shared", systemImage: "person.2").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).disabled(stock <= 0)
                    Button {
                        let buy = store.quickBuy(item)
                        showUndo($undo, "Added \(unit.label(buy.amount))") { store.undoBuy(buy) }
                    } label: { Label("Bought", systemImage: "plus").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                }
                .labelStyle(.titleAndIcon)
                Button("Log a purchase with price and shop") { buying = PurchaseTarget(itemId: id, eventId: 0) }
                Toggle(isOn: Binding(get: { item.refillNeeded }, set: { store.setRefillNeeded(id, $0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Refill needed")
                        Text(item.refillNeeded ? "On your shopping list" : "Put it on the shopping list now").font(.caption).foregroundColor(.secondary)
                    }
                }
                if item.hiddenFromList {
                    HStack {
                        Text("Hidden from the shopping list until you buy it again.").font(.caption).foregroundColor(.secondary)
                        Spacer()
                        Button("Show again") { store.setHidden(id, false) }.buttonStyle(.borderless)
                    }
                }
            }

            Section {
                InfoRow(label: "Category", value: "\(Categories.emojiFor(item.category)) \(item.category)")
                InfoRow(label: "Lasts", value: item.lifeValue.label, note: item.lifeValue.description)
                InfoRow(label: "Running low at", value: unit.label(item.lowAt))
                InfoRow(label: "Each tap", value: "− uses \(unit.label(item.step))", note: "＋ adds \(unit.label(Pantry.buyAmount(unit, entries)))")
                if let rate = Pantry.dailyUse(entries, today) {
                    let perWeek = Qty.approx(rate * 7)
                    InfoRow(label: "You use", value: "about \(perWeek) \(perWeek == "1" ? unit.singular : unit.plural) a week")
                }
                if let last = lastPriced, let unitPrice = Pantry.unitPriceMinor(last) {
                    InfoRow(label: "Last price", value: "\(Money.format(unitPrice, store.currency)) \(unit.priceLabel)",
                            note: [last.shop, last.date.format(.long)].filter { !$0.isEmpty }.joined(separator: " · "))
                }
            }

            if !item.notes.isEmpty {
                Section("Notes") { Text(item.notes) }
            }

            Section("History") {
                if history.isEmpty {
                    Text("Nothing logged yet. Tap Bought when you buy it.").foregroundColor(.secondary)
                }
                ForEach(history.prefix(100)) { e in
                    Button { openEvent = e } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(historyTitle(e, unit)).foregroundColor(.primary)
                                Text([e.date.format(.long), e.shop].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            if let p = e.priceMinor { Text(Money.format(p, store.currency)).foregroundColor(.primary) }
                        }
                    }
                }
            }

            Section {
                Button("Delete item", role: .destructive) { confirmDelete = true }
            }
        }
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) { Button("Edit") { editing = true } }
        }
        .sheet(isPresented: $editing) { EditItemView(existing: item) }
        .sheet(item: $buying) { target in PurchaseView(target: target, today: today) }
        .confirmationDialog(openEvent.map { historyTitle($0, unit) } ?? "", isPresented: Binding(get: { openEvent != nil }, set: { if !$0 { openEvent = nil } }), titleVisibility: .visible) {
            if let e = openEvent {
                if e.typeValue == .BOUGHT {
                    Button("Edit purchase") { buying = PurchaseTarget(itemId: id, eventId: e.id) }
                }
                if !e.receiptFile.isEmpty {
                    Button("View receipt photo") { receipt = e.receiptFile }
                }
                Button("Delete", role: .destructive) { store.deleteEvent(e.id) }
            }
        } message: {
            if let e = openEvent {
                Text([e.date.format(.long), e.priceMinor.map { "Paid \(Money.format($0, store.currency))" } ?? "", e.shop, e.note]
                    .filter { !$0.isEmpty }.joined(separator: " · "))
            }
        }
        .sheet(isPresented: Binding(get: { receipt != nil }, set: { if !$0 { receipt = nil } })) {
            NavigationStack {
                Group {
                    if let image = PhotoStore.load(receipt ?? "") {
                        Image(uiImage: image).resizable().scaledToFit()
                    } else {
                        Text("The photo couldn't be opened.")
                    }
                }
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { receipt = nil } } }
            }
        }
        .alert("Delete \(item.name)?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                store.deleteItem(id)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the item, its photos and its whole history. It can't be undone.")
        }
        .undoOverlay($undo)
    }
}

struct PurchaseTarget: Identifiable {
    let itemId: Int64
    /// 0 for a new purchase.
    let eventId: Int64
    var id: String { "\(itemId)-\(eventId)" }
}
