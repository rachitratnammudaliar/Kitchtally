import SwiftUI
import UniformTypeIdentifiers

struct ShoppingView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @State private var buying: PurchaseTarget?
    @State private var undo: UndoState?
    @State private var path: [Int64] = []
    @State private var newText = ""
    @State private var info: String?
    /// Items ticked while shopping, for "Done shopping".
    @State private var ticked = Set<Int64>()
    @State private var askNewWeek = false

    private func add() {
        let text = newText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        if store.addNote(text) { info = "\(text) is in your pantry, so it's marked as needing a refill." }
        newText = ""
    }

    var body: some View {
        let list = store.items.filter { store.onShoppingList($0, today) }
        // Grouped by where you usually buy each thing.
        let byShop = Dictionary(grouping: list) { Pantry.lastShop(store.entries($0.id)) }
        let shops = shopOrder(Array(byShop.keys))
        let tickedNow = list.filter { ticked.contains($0.id) }
        let estimate = list.reduce(Int64(0)) { $0 + (Pantry.expectedPriceMinor($1.unitValue, store.entries($1.id), packSize: $1.packSize) ?? 0) }
        let hidden = store.items.filter { $0.hiddenFromList && store.status($0, today) != .ok }

        NavigationStack(path: $path) {
            List {
                Section {
                    HStack {
                        TextField("Add something to buy", text: $newText)
                            .textInputAutocapitalization(.sentences)
                            .submitLabel(.done)
                            .onSubmit(add)
                        Button(action: add) { Image(systemName: "plus.circle.fill").font(.title2) }
                            .buttonStyle(.borderless)
                            .disabled(newText.trimmingCharacters(in: .whitespaces).isEmpty)
                            .accessibilityLabel("Add")
                    }
                    if let info = info { Text(info).font(.caption).foregroundColor(.secondary) }
                }

                if list.isEmpty && store.notes.isEmpty {
                    VStack(spacing: 10) {
                        Text("🛒").font(.system(size: 44))
                        Text("Nothing to buy").font(.headline)
                        Text("Pantry items appear here when they run out, run low, or you mark them as needing a refill. You can also type anything above.")
                            .font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                } else {
                    Text("\(list.count + store.notes.count) to buy" + (estimate > 0 ? " · about \(Money.format(estimate, store.currency)) at last prices" : "")
                         + (list.isEmpty ? "" : ". Tick what you bought, then tap Done shopping."))
                        .font(.subheadline).foregroundColor(.secondary)
                }

                if !store.notes.isEmpty {
                    Section("📝  Also need") {
                        ForEach(store.notes) { note in
                            Button {
                                store.deleteNote(note.id)
                                showUndo($undo, "Got \(note.text)") { store.restoreNote(note) }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "circle").foregroundColor(.secondary)
                                    Text(note.text).foregroundColor(.primary)
                                }
                            }
                        }
                    }
                }

                ForEach(shops, id: \.self) { shop in
                    let rows = (byShop[shop] ?? []).sorted { a, b in
                        let sa = store.status(a, today).rawValue, sb = store.status(b, today).rawValue
                        return sa != sb ? sa < sb : a.name.lowercased() < b.name.lowercased()
                    }
                    Section(shop.isEmpty ? "🛒  Anywhere · \(rows.count)" : "🏪  \(shop) · \(rows.count)") {
                        ForEach(rows) { item in row(item) }
                    }
                }

                if !hidden.isEmpty {
                    Text("Hidden until bought: " + hidden.map(\.name).joined(separator: ", ")).font(.caption).foregroundColor(.secondary)
                }
            }
            .navigationTitle("Shopping list")
            .toolbar {
                if !list.isEmpty || !store.notes.isEmpty {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        ShareLink(item: shoppingText(store, today)) { Image(systemName: "square.and.arrow.up") }
                            .accessibilityLabel("Share list")
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !tickedNow.isEmpty {
                    Button {
                        let items = tickedNow
                        ticked.removeAll()
                        if let result = store.doneShopping(items) { showUndo($undo, result.0, result.1) }
                        // Late in the week? Offer to start the new week, so this shop counts in it.
                        let weekStart = Pantry.weekStart(today, first: store.weekStartDay, anchor: store.weekAnchor)
                        if today.epochDay - weekStart.epochDay >= 5 { askNewWeek = true }
                    } label: {
                        Label("Done shopping · \(tickedNow.count)", systemImage: "checkmark").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .padding()
                }
            }
            .navigationDestination(for: Int64.self) { id in ItemView(id: id, today: today) }
            .sheet(item: $buying) { target in PurchaseView(target: target, today: today) }
            .undoOverlay($undo)
            .alert("Start the new week now?", isPresented: $askNewWeek) {
                Button("Start") { store.startNewWeek(today) }
                Button("Not now", role: .cancel) {}
            } message: {
                Text("Today's shop will count in the new week.")
            }
        }
    }

    private func row(_ item: Item) -> some View {
        let entries = store.entries(item.id)
        let unit = item.unitValue
        let expected = Pantry.expectedPriceMinor(unit, entries, packSize: item.packSize)
        let last = Pantry.lastPriced(entries)
        let shop = last?.shop.isEmpty == false ? last!.shop : (Pantry.lastBought(entries)?.shop ?? "")
        let status = store.status(item, today)
        _ = shop
        let line = "Usually \(unit.label(Pantry.buyAmount(unit, entries, packSize: item.packSize)))"
            + (expected.map { " · \(Money.format($0, store.currency))" } ?? "")
        let isTicked = ticked.contains(item.id)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Button {
                    if isTicked { ticked.remove(item.id) } else { ticked.insert(item.id) }
                } label: {
                    Image(systemName: isTicked ? "checkmark.circle.fill" : "circle").font(.title2)
                        .foregroundColor(isTicked ? .accentColor : .secondary)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(isTicked ? "Ticked" : "Tick")
                ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 46)
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.name).font(.headline).lineLimit(1)
                    StockPill(text: stockLabel(item, Pantry.stock(entries)), status: status)
                    Text(line).font(.caption).foregroundColor(.secondary).lineLimit(2)
                }
                Spacer(minLength: 4)
            }
            .contentShape(Rectangle())
            .onTapGesture { path.append(item.id) }
            HStack {
                Spacer()
                Menu("Not now") {
                    Button("Hide until I buy it") {
                        if item.refillNeeded && status == .ok {
                            store.setRefillNeeded(item.id, false)
                        } else {
                            store.setHidden(item.id, true)
                            showUndo($undo, "\(item.name) hidden until you buy it again") { store.setHidden(item.id, false) }
                        }
                    }
                    Button("Not sure to buy again") {
                        store.setUnsure(item.id, true)
                        showUndo($undo, "\(item.name) moved to \"Not sure to buy again\"") { store.setUnsure(item.id, false) }
                    }
                }
                .buttonStyle(.borderless)
                Button("Price & shop") { buying = PurchaseTarget(itemId: item.id, eventId: 0) }
                    .buttonStyle(.borderless)
                Button("Bought") {
                    let buy = store.quickBuy(item)
                    showUndo($undo, "Bought \(unit.label(buy.amount)) of \(item.name)") { store.undoBuy(buy) }
                }
                .buttonStyle(.borderedProminent).controlSize(.small)
            }
            .font(.subheadline)
        }
        .padding(.vertical, 2)
    }
}

struct WeekView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @State private var offset = 0
    @State private var path: [Int64] = []
    @State private var undoAnchor: Int? = nil

    var body: some View {
        let start = Pantry.weekStart(today, first: store.weekStartDay, anchor: store.weekAnchor).plusDays(-7 * offset)
        let byItem = store.entriesByItem
        let categoryOf: (Int64) -> String = { id in store.item(id)?.category ?? "Other" }
        let week = Pantry.week(start, byItem, categoryOf: categoryOf, isLong: { store.isLong($0) })
        let previous = Pantry.week(start.plusDays(-7), byItem, categoryOf: categoryOf, isLong: { store.isLong($0) })
        let diff = week.spentMinor - previous.spentMinor
        let bought = week.items.filter { $0.bought > 0 }.sorted { (store.item($0.itemId)?.name.lowercased() ?? "") < (store.item($1.itemId)?.name.lowercased() ?? "") }
        let used = week.items.filter { $0.used > 0 }

        NavigationStack(path: $path) {
            List {
                Section {
                    HStack {
                        Button { offset += 1 } label: { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                        Spacer()
                        Text("\(start.format(.short)) – \(start.plusDays(6).format(.short))").font(.headline)
                        Spacer()
                        Button { offset -= 1 } label: { Image(systemName: "chevron.right") }.buttonStyle(.borderless).disabled(offset == 0)
                    }
                }

                if offset == 0 {
                    Section {
                        if let old = undoAnchor {
                            Text("New week started today. Everything from today counts in it.").font(.subheadline)
                            Button("Undo") { store.setWeekAnchor(old); undoAnchor = nil }
                        } else {
                            Text("Bought your shop for the new week? Start it now and today counts in it.").font(.subheadline)
                            Button("Start new week") { undoAnchor = store.startNewWeek(today) }
                                .buttonStyle(.borderedProminent)
                        }
                    }
                }

                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(offset == 0 ? "New this week" : "That week").font(.subheadline).foregroundColor(.secondary)
                        Text(week.itemsBought == 0 && week.itemsUsed == 0 && !week.longItems.isEmpty ? "\(week.longItems.count) long-lasting thing\(week.longItems.count == 1 ? "" : "s") bought"
                             : week.itemsBought == 0 && week.itemsUsed == 0 ? "Nothing logged yet"
                             : week.itemsBought == 0 ? "\(week.itemsUsed) item\(week.itemsUsed == 1 ? "" : "s") used"
                             : "\(week.itemsBought) thing\(week.itemsBought == 1 ? "" : "s") bought")
                            .font(.title3.weight(.semibold))
                        HStack(spacing: 8) {
                            StatTile(value: "\(week.itemsBought)", label: "bought")
                            StatTile(value: "\(week.itemsUsed)", label: "used")
                            StatTile(value: "\(week.timesShared)", label: "times shared")
                        }
                        .padding(.top, 8)
                        if week.cravingMinor > 0 && store.showSpending {
                            Text("🍫 \(Money.format(week.cravingMinor, store.currency)) on cravings (could have skipped)").font(.subheadline).padding(.top, 4)
                        }
                    }
                    .padding(.vertical, 6)
                    .listRowBackground(Color(rgb: 0x2E6B3A).opacity(0.12))
                }

                if week.items.isEmpty && week.longItems.isEmpty {
                    Text("Nothing used or bought this week. Tap − or 🍳 Cook a meal when you use something, and it shows up here.")
                        .foregroundColor(.secondary)
                }
                if !bought.isEmpty {
                    Section("Bought") {
                        ForEach(bought, id: \.itemId) { w in
                            if let item = store.item(w.itemId) {
                                weekRow(item, "Bought \(item.unitValue.label(w.bought))", store.showSpending && w.spentMinor > 0 ? Money.format(w.spentMinor, store.currency) : nil)
                            }
                        }
                    }
                }
                if !used.isEmpty {
                    Section("Used") {
                        ForEach(used, id: \.itemId) { w in
                            if let item = store.item(w.itemId) {
                                let unit = item.unitValue
                                weekRow(item, "Used \(unit.label(w.used))" + (w.shared > 0 ? " (\(unit.label(w.shared)) shared)" : ""), nil)
                            }
                        }
                    }
                }

                if !week.longItems.isEmpty {
                    Section {
                        ForEach(week.longItems, id: \.itemId) { w in
                            if let item = store.item(w.itemId) {
                                let text = w.bought > 0 ? "Bought \(item.unitValue.label(w.bought))" : "In use"
                                weekRow(item, text, store.showSpending && w.spentMinor > 0 ? Money.format(w.spentMinor, store.currency) : nil)
                            }
                        }
                    } header: {
                        Text("Long-lasting things")
                    } footer: {
                        let monthLong = Pantry.month(today.firstOfMonth, byItem, categoryOf: categoryOf, isLong: { store.isLong($0) }).longMinor
                        Text("Face cream, perfume, dishwashing liquid and other things that last a month or more are kept out of the lists above."
                             + (store.showSpending && monthLong > 0 ? " Spent on them this month: \(Money.format(monthLong, store.currency))." : ""))
                    }
                }

                // Money stays out of the way until you ask for it.
                Section {
                    Button(store.showSpending ? "Hide spending" : "Show spending") { store.showSpending.toggle() }
                        .frame(maxWidth: .infinity)
                }
                if store.showSpending {
                    Section("Spent") {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(Money.format(week.spentMinor, store.currency)).font(.system(size: 30, weight: .semibold, design: .rounded))
                            if previous.spentMinor > 0 {
                                Text(diff > 0 ? "\(Money.format(diff, store.currency)) more than the week before"
                                     : diff < 0 ? "\(Money.format(-diff, store.currency)) less than the week before" : "Same as the week before")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                        if store.budgetMinor > 0 {
                            let month = Pantry.spentBetween(byItem.filter { !store.isLong($0.key) }, today.firstOfMonth, today)
                            VStack(alignment: .leading, spacing: 6) {
                                Text("This month: \(Money.format(month, store.currency)) of \(Money.format(store.budgetMinor, store.currency))").fontWeight(.medium)
                                ProgressView(value: min(Double(month), Double(store.budgetMinor)), total: Double(store.budgetMinor))
                                Text(month <= store.budgetMinor ? "\(Money.format(store.budgetMinor - month, store.currency)) left this month"
                                     : "\(Money.format(month - store.budgetMinor, store.currency)) over this month")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                        let maxValue = week.spentByCategory.values.max() ?? 1
                        ForEach(week.spentByCategory.keys.sorted { (week.spentByCategory[$0] ?? 0) > (week.spentByCategory[$1] ?? 0) }, id: \.self) { category in
                            let minor = week.spentByCategory[category] ?? 0
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text("\(Categories.emojiFor(category))  \(category)")
                                    Spacer()
                                    Text(Money.format(minor, store.currency)).fontWeight(.medium)
                                }
                                ProgressView(value: Double(minor), total: Double(maxValue))
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
            }
            .navigationTitle(offset == 0 ? "This week" : "Weekly")
            .navigationDestination(for: Int64.self) { id in ItemView(id: id, today: today) }
        }
    }

    func weekRow(_ item: Item, _ text: String, _ money: String?) -> some View {
        HStack(spacing: 12) {
            ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(.subheadline.weight(.semibold))
                Text(text).font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            if let money = money { Text(money).font(.subheadline.weight(.medium)) }
        }
        .contentShape(Rectangle())
        .onTapGesture { path.append(item.id) }
    }
}

/// "Shopping for next week": purchases logged while it is on count in next week's summary.
struct ForNextWeekSection: View {
    @EnvironmentObject private var store: Store
    let today: Day

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { store.isForNextWeek(today) }, set: { store.setForNextWeek($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Shopping for next week")
                    Text("Things you buy now count in next week's summary").font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var importing = false
    @State private var exportURL: URL?
    @State private var message: String?
    @State private var budgetText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Currency", selection: $store.currency) {
                        ForEach(Money.currencies, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Currency")
                } footer: {
                    Text("Changing it doesn't convert amounts you've already entered.")
                }

                Section {
                    Picker("Week starts on", selection: $store.weekStartDay) {
                        ForEach(1...7, id: \.self) { Text(Calendar.current.weekdaySymbols[$0 % 7]).tag($0) }
                    }
                } header: {
                    Text("Week")
                } footer: {
                    Text("The Weekly tab counts seven days from this day. To count a shop in next week, use the button on Weekly.")
                }

                Section {
                    Toggle("Show spending on Weekly", isOn: $store.showSpending)
                    HStack {
                        TextField("Monthly budget (optional)", text: $budgetText).keyboardType(.decimalPad)
                        Button("Save") {
                            store.budgetMinor = budgetText.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : (Money.parseToMinor(budgetText) ?? store.budgetMinor)
                            budgetText = store.budgetMinor > 0 ? Money.minorToInput(store.budgetMinor) : ""
                        }
                        .disabled(!budgetText.trimmingCharacters(in: .whitespaces).isEmpty && Money.parseToMinor(budgetText) == nil)
                    }
                } header: {
                    Text("Spending")
                } footer: {
                    Text("Off keeps money out of sight until you tap Show spending. The budget only shows there. Leave it empty to turn it off.")
                }

                Section {
                    Text("Save your items and history to a file, and restore them on a new phone, Android or iPhone. Photos aren't included.")
                    if let url = exportURL {
                        ShareLink(item: url) { Label("Export backup", systemImage: "square.and.arrow.up") }
                    }
                    Button { importing = true } label: { Label("Import backup", systemImage: "square.and.arrow.down") }
                    if let message = message { Text(message).font(.footnote).foregroundColor(.secondary) }
                } header: {
                    Text("Backup")
                }

                Section {
                    let info = Bundle.main.infoDictionary
                    let version = (info?["CFBundleShortVersionString"] as? String) ?? ""
                    ShareLink(item: "Kitchtally \(version)\niOS \(UIDevice.current.systemVersion), \(UIDevice.current.model)\n\nWhat happened:\n",
                              subject: Text("Kitchtally problem")) {
                        Label("Report a problem", systemImage: "exclamationmark.bubble")
                    }
                } header: {
                    Text("Something wrong?")
                } footer: {
                    Text("Sends nothing by itself. It opens your share menu with the app version and your iPhone model, so you can send it to the developer.")
                }

                Section("Privacy") {
                    Text("Kitchtally works completely offline. No account, no ads and no tracking. Your items, prices and photos stay on this iPhone.")
                }

                Section {
                    Text("Kitchtally \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")")
                    Link("github.com/rachitratnammudaliar/kitchtally", destination: URL(string: "https://github.com/rachitratnammudaliar/kitchtally")!)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear {
                exportURL = try? store.exportFile()
                budgetText = store.budgetMinor > 0 ? Money.minorToInput(store.budgetMinor) : ""
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
                switch result {
                case .success(let url):
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    do {
                        let count = try store.importData(try Data(contentsOf: url))
                        message = "Added \(count) items from the backup."
                        exportURL = try? store.exportFile()
                    } catch {
                        message = error.localizedDescription
                    }
                case .failure(let error):
                    message = error.localizedDescription
                }
            }
        }
    }
}
