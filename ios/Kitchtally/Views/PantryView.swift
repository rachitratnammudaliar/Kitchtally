import SwiftUI

struct PantryView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @Binding var tab: Int

    @State private var filter = ""
    @State private var adding = false
    @State private var showSettings = false
    @State private var undo: UndoState?
    @State private var path: [Int64] = []

    private func act(_ message: String, _ eventId: Int64) {
        withAnimation { undo = UndoState(message: message, eventId: eventId) }
        let current = undo
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            if undo == current { withAnimation { undo = nil } }
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                summary
                if store.items.isEmpty {
                    Section {
                        VStack(spacing: 10) {
                            Text("🫙").font(.system(size: 44))
                            Text("Nothing here yet").font(.headline)
                            Text("Add the things you keep at home: rice, bread, atta, onions, shampoo. Then tap + when you buy more and − when you use some.")
                                .font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center)
                            Button("Add your first item") { adding = true }.buttonStyle(.borderedProminent)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                    }
                } else {
                    let categories = Categories.ordered(store.usedCategories)
                    if categories.count > 1 {
                        Section {
                            Picker("Show", selection: $filter) {
                                Text("All").tag("")
                                ForEach(categories, id: \.self) { Text("\(Categories.emojiFor($0)) \($0)").tag($0) }
                            }
                        }
                    }
                    ForEach(categories.filter { filter.isEmpty || $0 == filter }, id: \.self) { category in
                        let rows = store.items.filter { $0.category.lowercased() == category.lowercased() }
                            .sorted { $0.name.lowercased() < $1.name.lowercased() }
                        Section(header: Text("\(Categories.emojiFor(category))  \(category) · \(rows.count)")) {
                            ForEach(rows) { item in
                                ItemRow(item: item, today: today, onAct: act) { path.append(item.id) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Kitchtally")
            .navigationDestination(for: Int64.self) { id in ItemView(id: id, today: today) }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }.accessibilityLabel("Add item")
                }
            }
            .sheet(isPresented: $adding) { EditItemView(existing: nil) }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .overlay(alignment: .bottom) {
                if let u = undo {
                    UndoToast(message: u.message) {
                        store.deleteEvent(u.eventId)
                        withAnimation { undo = nil }
                    }
                }
            }
        }
    }

    private var summary: some View {
        let week = Pantry.week(Pantry.weekStart(today), store.entriesByItem) { id in store.item(id)?.category ?? "" }
        let toBuy = store.items.filter { store.onShoppingList($0, today) }.count
        return Section {
            VStack(alignment: .leading, spacing: 4) {
                Text("This week").font(.subheadline).foregroundColor(.secondary)
                Text(Money.format(week.spentMinor, store.currency)).font(.system(size: 34, weight: .semibold, design: .rounded))
                Text("spent on groceries and more").font(.caption).foregroundColor(.secondary)
                HStack(spacing: 8) {
                    StatTile(value: Qty.format(week.usedTotal), label: "used").onTapGesture { tab = 2 }
                    StatTile(value: Qty.format(week.sharedTotal), label: "shared").onTapGesture { tab = 2 }
                    StatTile(value: "\(toBuy)", label: "to buy").onTapGesture { tab = 1 }
                }
                .padding(.top, 8)
            }
            .padding(.vertical, 6)
            .listRowBackground(Color(rgb: 0x2E6B3A).opacity(0.12))
        }
    }
}

struct ItemRow: View {
    @EnvironmentObject private var store: Store
    let item: Item
    let today: Day
    let onAct: (String, Int64) -> Void
    let onOpen: () -> Void

    var body: some View {
        let stock = store.stock(item.id)
        let status = store.status(item, today)
        let unit = item.unitValue
        HStack(spacing: 12) {
            HStack(spacing: 12) {
                ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 50)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.name).font(.headline).lineLimit(1)
                    StockPill(text: stockLabel(item, stock) + (item.refillNeeded && status == .ok ? " · refill" : ""), status: status)
                    Text(detailLine(item, store, today)).font(.caption).foregroundColor(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            .accessibilityAddTraits(.isButton)
            HStack(spacing: 6) {
                Button {
                    onAct("Shared 1 \(unit.singular) of \(item.name)", store.log(item.id, .SHARED))
                } label: { Image(systemName: "person.2").frame(width: 34, height: 34) }
                    .buttonStyle(.bordered).disabled(stock <= 0).accessibilityLabel("Shared 1 with friends")
                Button {
                    onAct("Used 1 \(unit.singular) of \(item.name)", store.log(item.id, .USED))
                } label: { Image(systemName: "minus").frame(width: 34, height: 34) }
                    .buttonStyle(.bordered).disabled(stock <= 0).accessibilityLabel("Used 1")
                Button {
                    onAct("Added 1 \(unit.singular) of \(item.name)", store.quickBuy(item.id))
                } label: { Image(systemName: "plus").frame(width: 34, height: 34) }
                    .buttonStyle(.borderedProminent).accessibilityLabel("Bought 1 more")
            }
            .buttonBorderShape(.roundedRectangle(radius: 10))
            .controlSize(.small)
        }
        .padding(.vertical, 4)
    }
}
