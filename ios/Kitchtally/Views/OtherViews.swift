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

    private func add() {
        let text = newText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        if store.addNote(text) { info = "\(text) is in your pantry, so it's marked as needing a refill." }
        newText = ""
    }

    var body: some View {
        let list = store.items.filter { store.onShoppingList($0, today) }
        let titles = ["Out", "Running low", "Refill needed"]
        let byTitle: [String: [Item]] = [
            "Out": list.filter { store.status($0, today) == .out },
            "Running low": list.filter { store.status($0, today) == .low },
            "Refill needed": list.filter { store.status($0, today) == .ok },
        ]
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
                    Text("\(list.count + store.notes.count) to buy" + (estimate > 0 ? " · about \(Money.format(estimate, store.currency)) at last prices" : ""))
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

                ForEach(titles.filter { !(byTitle[$0] ?? []).isEmpty }, id: \.self) { title in
                    Section(title) {
                        ForEach((byTitle[title] ?? []).sorted { $0.name.lowercased() < $1.name.lowercased() }) { item in
                            row(item)
                        }
                    }
                }

                if !hidden.isEmpty {
                    Text("Hidden until bought: " + hidden.map(\.name).joined(separator: ", ")).font(.caption).foregroundColor(.secondary)
                }
            }
            .navigationTitle("Shopping list")
            .navigationDestination(for: Int64.self) { id in ItemView(id: id, today: today) }
            .sheet(item: $buying) { target in PurchaseView(target: target, today: today) }
            .undoOverlay($undo)
        }
    }

    private func row(_ item: Item) -> some View {
        let entries = store.entries(item.id)
        let unit = item.unitValue
        let expected = Pantry.expectedPriceMinor(unit, entries, packSize: item.packSize)
        let last = Pantry.lastPriced(entries)
        let shop = last?.shop.isEmpty == false ? last!.shop : (Pantry.lastBought(entries)?.shop ?? "")
        let status = store.status(item, today)
        let line = "Usually \(unit.label(Pantry.buyAmount(unit, entries, packSize: item.packSize)))"
            + (expected.map { " · \(Money.format($0, store.currency))" } ?? "")
            + (shop.isEmpty ? "" : " at \(shop)")
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
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
                Button("Not now") {
                    if item.refillNeeded && status == .ok {
                        store.setRefillNeeded(item.id, false)
                    } else {
                        store.setHidden(item.id, true)
                        showUndo($undo, "\(item.name) hidden until you buy it again") { store.setHidden(item.id, false) }
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

    var body: some View {
        let start = Pantry.weekStart(today).plusDays(-7 * offset)
        let byItem = store.entriesByItem
        let categoryOf: (Int64) -> String = { id in store.item(id)?.category ?? "Other" }
        let week = Pantry.week(start, byItem, categoryOf: categoryOf)
        let previous = Pantry.week(start.plusDays(-7), byItem, categoryOf: categoryOf)
        let diff = week.spentMinor - previous.spentMinor

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

                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Spent").font(.subheadline).foregroundColor(.secondary)
                        Text(Money.format(week.spentMinor, store.currency)).font(.system(size: 34, weight: .semibold, design: .rounded))
                        if week.spentMinor > 0 || previous.spentMinor > 0 {
                            Text(diff > 0 ? "\(Money.format(diff, store.currency)) more than the week before"
                                 : diff < 0 ? "\(Money.format(-diff, store.currency)) less than the week before" : "Same as the week before")
                                .font(.caption).foregroundColor(.secondary)
                        }
                        HStack(spacing: 8) {
                            StatTile(value: "\(week.itemsUsed)", label: "items used")
                            StatTile(value: "\(week.timesShared)", label: "times shared")
                            StatTile(value: "\(week.itemsBought)", label: "items bought")
                        }
                        .padding(.top, 8)
                    }
                    .padding(.vertical, 6)
                    .listRowBackground(Color(rgb: 0x2E6B3A).opacity(0.12))
                }

                if !week.spentByCategory.isEmpty {
                    Section("Spent by category") {
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

                Section("Items") {
                    if week.items.isEmpty {
                        Text("Nothing used or bought this week.").foregroundColor(.secondary)
                    }
                    ForEach(week.items, id: \.itemId) { w in
                        if let item = store.item(w.itemId) {
                            let unit = item.unitValue
                            let parts = [
                                w.used > 0 ? "used \(unit.label(w.used))" + (w.shared > 0 ? " (\(Qty.format(w.shared)) shared)" : "") : "",
                                w.bought > 0 ? "bought \(unit.label(w.bought))" : "",
                            ].filter { !$0.isEmpty }.joined(separator: " · ")
                            HStack(spacing: 12) {
                                ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.name).font(.subheadline.weight(.semibold))
                                    Text(parts.prefix(1).uppercased() + String(parts.dropFirst())).font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                if w.spentMinor > 0 { Text(Money.format(w.spentMinor, store.currency)).font(.subheadline.weight(.medium)) }
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { path.append(item.id) }
                        }
                    }
                }
            }
            .navigationTitle(offset == 0 ? "This week" : "Weekly")
            .navigationDestination(for: Int64.self) { id in ItemView(id: id, today: today) }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var importing = false
    @State private var exportURL: URL?
    @State private var message: String?

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
                    Text("Save your items and history to a file, and restore them on a new phone, Android or iPhone. Photos aren't included.")
                    if let url = exportURL {
                        ShareLink(item: url) { Label("Export backup", systemImage: "square.and.arrow.up") }
                    }
                    Button { importing = true } label: { Label("Import backup", systemImage: "square.and.arrow.down") }
                    if let message = message { Text(message).font(.footnote).foregroundColor(.secondary) }
                } header: {
                    Text("Backup")
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
            .onAppear { exportURL = try? store.exportFile() }
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
