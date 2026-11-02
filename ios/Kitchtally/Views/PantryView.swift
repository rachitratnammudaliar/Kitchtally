import SwiftUI

struct PantryView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @Binding var tab: Int

    @State private var filter = ""
    @State private var search = ""
    @State private var adding = false
    @State private var showSettings = false
    @State private var undo: UndoState?
    @State private var path: [Int64] = []

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
                    let query = search.trimmingCharacters(in: .whitespaces).lowercased()
                    let shown = store.items.filter {
                        query.isEmpty || $0.name.lowercased().contains(query) || $0.notes.lowercased().contains(query)
                    }
                    if categories.count > 1 {
                        Section {
                            Picker("Show", selection: $filter) {
                                Text("All").tag("")
                                ForEach(categories, id: \.self) { Text("\(Categories.emojiFor($0)) \($0)").tag($0) }
                            }
                        }
                    }
                    if shown.isEmpty {
                        Text("Nothing matches \"\(search)\"").foregroundColor(.secondary)
                    }
                    ForEach(categories.filter { filter.isEmpty || $0 == filter }, id: \.self) { category in
                        let rows = shown.filter { !$0.unsure && $0.category.lowercased() == category.lowercased() }
                            .sorted { $0.name.lowercased() < $1.name.lowercased() }
                        if !rows.isEmpty {
                            Section(header: Text("\(Categories.emojiFor(category))  \(category) · \(rows.count)")) {
                                ForEach(rows) { item in
                                    ItemRow(item: item, today: today, undo: $undo) { path.append(item.id) }
                                }
                            }
                        }
                    }
                    let unsure = shown.filter { $0.unsure && (filter.isEmpty || $0.category == filter) }.sorted { $0.name.lowercased() < $1.name.lowercased() }
                    if !unsure.isEmpty {
                        Section(header: Text("🤔  Not sure to buy again · \(unsure.count)")) {
                            ForEach(unsure) { item in
                                ItemRow(item: item, today: today, undo: $undo) { path.append(item.id) }
                            }
                        }
                    }
                }
            }
            .searchable(text: $search, prompt: "Search your pantry")
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
            .undoOverlay($undo)
        }
    }

    private var summary: some View {
        let week = Pantry.week(Pantry.weekStart(today), store.entriesByItem) { id in store.item(id)?.category ?? "" }
        let toBuy = store.items.filter { store.onShoppingList($0, today) }.count + store.notes.count
        return Section {
            VStack(alignment: .leading, spacing: 4) {
                Text("This week").font(.subheadline).foregroundColor(.secondary)
                Text(Money.format(week.spentMinor, store.currency)).font(.system(size: 34, weight: .semibold, design: .rounded))
                Text("spent on groceries and more").font(.caption).foregroundColor(.secondary)
                HStack(spacing: 8) {
                    StatTile(value: "\(week.itemsUsed)", label: "items used").onTapGesture { tab = 2 }
                    StatTile(value: "\(week.timesShared)", label: "times shared").onTapGesture { tab = 2 }
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
    @Binding var undo: UndoState?
    let onOpen: () -> Void
    @State private var adjusting = false

    var body: some View {
        let stock = store.stock(item.id)
        let status = store.status(item, today)
        let unit = item.unitValue
        HStack(spacing: 12) {
            HStack(spacing: 12) {
                ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 50)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.name).font(.headline).lineLimit(1)
                    StockPill(text: stockLabel(item, stock) + (item.refillNeeded && status == .ok ? " · refill" : "") + "  ±", status: status) { adjusting = true }
                    Text(detailLine(item, store, today)).font(.caption).foregroundColor(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            .accessibilityAddTraits(.isButton)
            HStack(spacing: 6) {
                Button {
                    if let r = store.use(item, shared: true) {
                        showUndo($undo, "Shared \(unit.label(r.1)) of \(item.name)") { store.deleteEvent(r.0) }
                    }
                } label: { Image(systemName: "person.2").frame(width: 34, height: 34) }
                    .buttonStyle(.bordered).disabled(stock <= 0).accessibilityLabel("Shared with friends")
                Button {
                    if let r = store.use(item, shared: false) {
                        showUndo($undo, "Used \(unit.label(r.1)) of \(item.name)") { store.deleteEvent(r.0) }
                    }
                } label: { Image(systemName: "minus").frame(width: 34, height: 34) }
                    .buttonStyle(.bordered).disabled(stock <= 0).accessibilityLabel("Used some")
                Button {
                    let buy = store.quickBuy(item)
                    showUndo($undo, "Added \(unit.label(buy.amount)) of \(item.name)") { store.undoBuy(buy) }
                } label: { Image(systemName: "plus").frame(width: 34, height: 34) }
                    .buttonStyle(.borderedProminent).accessibilityLabel("Bought more")
            }
            .buttonBorderShape(.roundedRectangle(radius: 10))
            .controlSize(.small)
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $adjusting) {
            AdjustSheet(item: item) { message, action in showUndo($undo, "\(message) of \(item.name)", action) }
        }
    }
}
