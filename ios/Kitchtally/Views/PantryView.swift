import SwiftUI

/// Filter chips on the home page. Anything else is a category name.
enum HomeChip {
    static let low = "@low"
    static let expiring = "@expiring"
    static let long = "@long"
}

struct PantryView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @Binding var tab: Int

    @State private var filter = ""
    @State private var showOut = false
    @State private var search = ""
    @State private var adding = false
    @State private var showSettings = false
    @State private var showAgenda = false
    @State private var cooking = false
    @State private var showNew = false
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
                    if store.backupDue(today) {
                        Section {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("💾  Back up your pantry").font(.headline)
                                Text("It's been a month. Your data lives only on this phone. Settings → Backup saves a copy you can keep.").font(.caption).foregroundColor(.secondary)
                                HStack {
                                    Spacer()
                                    Button("Later") { store.snoozeBackup(today) }.buttonStyle(.borderless)
                                    Button("Open Settings") { showSettings = true }.buttonStyle(.borderless)
                                }
                            }
                        }
                    }
                    availableContent
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
            .onAppear { if store.shouldShowWhatsNew() { showNew = true } }
            .alert("What's new", isPresented: $showNew) {
                Button("Got it") { store.markWhatsNewSeen() }
            } message: {
                Text("📅 New Weekly budget: set it in Settings and see it on the home page. The Week agenda now estimates the cost of what you need to buy.\n\n📦 The home page lists what you have, with filter chips. Add best-before dates and get a reminder 2 days before.")
            }
            .sheet(isPresented: $adding) { EditItemView(existing: nil) }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(isPresented: $showAgenda) { WeekAgendaView(today: today) }
            .sheet(isPresented: $cooking) {
                CookSheet { message, action in showUndo($undo, message, action) }
            }
            .undoOverlay($undo)
        }
    }

    private func expiryOf(_ item: Item) -> Day? {
        Expiry.of(store.entries(item.id), stock: store.stock(item.id), itemExpiryDay: item.expiryDay)
    }

    private func sortKey(_ item: Item) -> (Int, Int, String) {
        let name = item.name.lowercased()
        if store.stock(item.id) > 0 && store.status(item, today) != .ok { return (0, 0, name) }
        if let e = expiryOf(item), e.epochDay - today.epochDay <= 7 { return (1, e.epochDay, name) }
        return (2, 0, name)
    }

    private func chip(_ key: String, _ title: String) -> some View {
        Button { filter = key } label: {
            Text(title).font(.subheadline)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(filter == key ? Color(rgb: 0x2E6B3A) : Color.secondary.opacity(0.15)))
                .foregroundColor(filter == key ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var availableContent: some View {
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        let searching = !query.isEmpty
        let shown = store.items.filter {
            !searching || $0.name.lowercased().contains(query) || $0.notes.lowercased().contains(query)
        }
        let categories = Categories.ordered(store.usedCategories)
        let hasLong = store.items.contains { store.isLong($0.id) }
        if !searching {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip("", "All")
                        chip(HomeChip.low, "⚠️ Running low")
                        chip(HomeChip.expiring, "⏳ Expiring soon")
                        if hasLong { chip(HomeChip.long, "🪥 Long-lasting") }
                        ForEach(categories, id: \.self) { c in chip(c, "\(Categories.emojiFor(c)) \(c)") }
                    }
                    .padding(.horizontal, 12)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
        if filter.isEmpty && !searching { stripSections }

        let inStock = shown.filter { store.stock($0.id) > 0 }
        let matching: [Item] = {
            if searching { return inStock }
            switch filter {
            case "": return inStock.filter { !store.isLong($0.id) }
            case HomeChip.low: return inStock.filter { store.status($0, today) != .ok }
            case HomeChip.expiring: return inStock.filter { (expiryOf($0)?.epochDay ?? Int.max) - today.epochDay <= 14 }
            case HomeChip.long: return inStock.filter { store.isLong($0.id) }
            default: return inStock.filter { $0.category.lowercased() == filter.lowercased() }
            }
        }()
        let list = matching.sorted { a, b in
            let x = sortKey(a), y = sortKey(b)
            if x.0 != y.0 { return x.0 < y.0 }
            if x.1 != y.1 { return x.1 < y.1 }
            return x.2 < y.2
        }
        let outOfStock: [Item] = {
            if filter == HomeChip.low || filter == HomeChip.expiring { return [] }
            return shown.filter { store.stock($0.id) <= 0 && (searching || filter.isEmpty || (filter == HomeChip.long ? store.isLong($0.id) : $0.category.lowercased() == filter.lowercased())) }
                .sorted { $0.name.lowercased() < $1.name.lowercased() }
        }()
        Section(header: Text("Available · \(list.count)")) {
            if list.isEmpty {
                Text(searching ? "Nothing matches \"\(search)\"" : "Nothing here right now").foregroundColor(.secondary)
            }
            ForEach(list) { item in
                ItemRow(item: item, today: today, undo: $undo) { path.append(item.id) }
            }
        }
        if !outOfStock.isEmpty {
            Section {
                Button { showOut.toggle() } label: {
                    HStack {
                        Text("Out of stock · \(outOfStock.count)").foregroundColor(.primary)
                        Spacer()
                        Image(systemName: showOut ? "chevron.up" : "chevron.down").foregroundColor(.secondary)
                    }
                }
                if showOut {
                    ForEach(outOfStock) { item in
                        ItemRow(item: item, today: today, undo: $undo) { path.append(item.id) }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var stripSections: some View {
        let needs = store.items.filter { store.onShoppingList($0, today) }
        let lowLong = store.items.filter {
            store.isLong($0.id) && !$0.noRebuy && !store.onShoppingList($0, today) && LongLasting.runningLow(store.entries($0.id), lastsDays: $0.lastsDays, today: today)
        }
        let useFirst = store.items.filter { item in
            store.stock(item.id) > 0 && (expiryOf(item).map { $0.epochDay - today.epochDay <= 3 } ?? false)
        }.sorted { (expiryOf($0)?.epochDay ?? 0) < (expiryOf($1)?.epochDay ?? 0) }
        if !needs.isEmpty {
            Section {
                Button { tab = 1 } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("🛒  Needs buying · \(needs.count)").font(.headline)
                        Text(needs.prefix(6).map(\.name).joined(separator: ", ") + (needs.count > 6 ? " and \(needs.count - 6) more" : ""))
                            .font(.subheadline).foregroundColor(.secondary)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        if !useFirst.isEmpty {
            Section(header: Text("⏳  Use first")) {
                ForEach(Array(useFirst.prefix(5))) { item in
                    let d = (expiryOf(item)?.epochDay ?? today.epochDay) - today.epochDay
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                            Text("Best before " + Expiry.label(d)).font(.caption).foregroundColor(d < 0 ? .red : .orange)
                        }
                        Spacer()
                        if d < 0 {
                            Button("Threw away") {
                                if let r = store.throwAway(item.id) {
                                    showUndo($undo, "Threw away \(item.name)") { store.deleteEvent(r) }
                                }
                            }.buttonStyle(.borderless)
                        }
                    }
                }
            }
        }
        if !lowLong.isEmpty {
            Section(header: Text("🪥  Long-lasting, coming up")) {
                ForEach(Array(lowLong.prefix(4))) { item in
                    let left = (LongLasting.runsOutOn(store.entries(item.id), lastsDays: item.lastsDays)?.epochDay ?? today.epochDay) - today.epochDay
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                            Text(left <= 0 ? "Running low about now" : "Running low in about \(left) days").font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Button("Add to shop") { store.setRefillNeeded(item.id, true) }.buttonStyle(.borderless)
                    }
                }
            }
        }
    }

    private var summary: some View {
        let week = Pantry.week(Pantry.weekStart(today, first: store.weekStartDay, anchor: store.weekAnchor), store.entriesByItem, categoryOf: { id in store.item(id)?.category ?? "" }, isLong: { store.isLong($0) })
        let toBuy = Set(store.items.filter { store.onShoppingList($0, today) }.map(\.id)).union(store.plannedNeeds().map { $0.item.id }).count + store.notes.count
        let almostOut = store.items.filter { !$0.unsure && store.stock($0.id) > 0 && store.status($0, today) != .ok }.count
        return Section {
            VStack(alignment: .leading, spacing: 4) {
                Text("This week").font(.subheadline).foregroundColor(.secondary)
                Text(almostOut == 1 ? "1 thing is almost out" : almostOut > 1 ? "\(almostOut) things are almost out" : "Your pantry is stocked")
                    .font(.title3.weight(.semibold))
                HStack(spacing: 8) {
                    StatTile(value: "\(week.itemsUsed)", label: "items used").onTapGesture { tab = 2 }
                    StatTile(value: "\(week.itemsBought)", label: "bought new").onTapGesture { tab = 2 }
                    StatTile(value: "\(toBuy)", label: "to buy").onTapGesture { tab = 1 }
                }
                .padding(.top, 8)
                if store.weeklyBudgetMinor > 0 {
                    let b = store.weeklyBudgetMinor, spent = week.spentMinor
                    VStack(alignment: .leading, spacing: 6) {
                        Text(spent <= b ? "💶  \(Money.format(spent, store.currency)) of \(Money.format(b, store.currency)) this week · \(Money.format(b - spent, store.currency)) left"
                             : "💶  \(Money.format(spent, store.currency)) of \(Money.format(b, store.currency)) this week · \(Money.format(spent - b, store.currency)) over")
                            .font(.subheadline.weight(.medium))
                        ProgressView(value: min(Double(spent), Double(b)), total: Double(b))
                    }
                    .padding(.top, 8)
                }
                Button { showAgenda = true } label: {
                    Text("📅  Week agenda").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
                if store.cookingOn {
                    Button { cooking = true } label: {
                        Text("🍳  Cook a meal").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 8)
                    .disabled(store.items.isEmpty)
                }
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
        let skip = store.skipping(item, today) || store.weekendTreatRest(item, today)
        // Skipping it for now: no red or amber warning, just a quiet tag.
        let status: StockStatus = skip ? .ok : store.status(item, today)
        let unit = item.unitValue
        HStack(spacing: 12) {
            HStack(spacing: 12) {
                ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 50)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.name).font(.headline).lineLimit(1)
                    StockPill(text: stockLabel(item, stock) + (skip && store.status(item, today) != .ok ? " · skipping" : (item.refillNeeded && status == .ok ? " · refill" : "")) + "  ±", status: status) { adjusting = true }
                    let alert = stock > 0 && status != .ok
                    Text(detailLine(item, store, today)).font(.caption)
                        .fontWeight(alert ? .medium : .regular)
                        .foregroundColor(alert ? statusColor(status) : .secondary).lineLimit(2)
                    if stock > 0, let e = Expiry.of(store.entries(item.id), stock: stock, itemExpiryDay: item.expiryDay), e.epochDay - today.epochDay <= 14 {
                        let d = e.epochDay - today.epochDay
                        Text("⏳ Best before " + Expiry.label(d)).font(.caption)
                            .foregroundColor(d < 0 ? .red : d <= 3 ? .orange : .secondary)
                    }
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

/// Cook a meal: tick what goes in; one meal comes off each (or one tap for things like oil).
struct CookSheet: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let onDone: (String, @escaping () -> Void) -> Void
    @State private var chosen = Set<Int64>()
    @State private var search = ""

    var body: some View {
        let available = store.items.filter { store.stock($0.id) > 0 }.sorted { a, b in
            let am = a.countMeals && a.mealsPerUnit > 0, bm = b.countMeals && b.mealsPerUnit > 0
            return am != bm ? am : a.name.lowercased() < b.name.lowercased()
        }
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        let shown = available.filter { query.isEmpty || $0.name.lowercased().contains(query) || chosen.contains($0.id) }
        NavigationStack {
            List {
                if available.isEmpty {
                    Text("Nothing in the pantry yet. Tap + on what you have first.").foregroundColor(.secondary)
                } else {
                    Section(footer: Text("Each one goes down by one meal, or by one tap for things like oil.")) {
                        ForEach(shown) { item in
                            let stock = store.stock(item.id)
                            let amount = Meals.cookAmount(item.step, item.countMeals ? item.mealsPerUnit : 0)
                            Button {
                                if chosen.contains(item.id) { chosen.remove(item.id) } else { chosen.insert(item.id) }
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: chosen.contains(item.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundColor(chosen.contains(item.id) ? .accentColor : .secondary)
                                    ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 34)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.name).foregroundColor(.primary)
                                        Text(([stockLabel(item, stock), mealsText(item, stock), "uses \(item.unitValue.label(min(amount, stock)))"].compactMap { $0 }).joined(separator: " · "))
                                            .font(.caption).foregroundColor(.secondary).lineLimit(1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $search, prompt: "Search")
            .navigationTitle("🍳 Cook a meal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("With friends") { finish(shared: true) }.disabled(chosen.isEmpty)
                    Spacer()
                    Button("Cooked") { finish(shared: false) }.buttonStyle(.borderedProminent).disabled(chosen.isEmpty)
                }
            }
        }
    }

    private func finish(shared: Bool) {
        let items = store.items.filter { chosen.contains($0.id) }
        if let result = store.cook(items, shared: shared) { onDone(result.0, result.1) }
        dismiss()
    }
}
