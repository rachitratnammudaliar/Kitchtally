import SwiftUI

/// Plan the next 7 days from your pantry. Whatever you won't have enough of goes on the shopping list in one tap.
struct WeekAgendaView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let today: Day
    @State private var picking: Int? = nil

    private var plan: [Int: [Int64]] { WeekPlan.upcoming(store.weekPlan, today: today.epochDay) }

    private func title(_ epoch: Int) -> String {
        let f = DateFormatter()
        f.dateFormat = "EEEE d MMM"
        return f.string(from: Day(epochDay: epoch).localDate) + (epoch == today.epochDay ? " · Today" : "")
    }

    var body: some View {
        let plan = self.plan
        let short: [(Item, Double)] = Array(Set(plan.values.flatMap { $0 })).compactMap { id in
            guard let item = store.item(id) else { return nil }
            let by = WeekPlan.shortBy(plan, id, step: item.step, stock: store.stock(id))
            return by > 0 ? (item, by) : nil
        }.sorted { $0.0.name.lowercased() < $1.0.name.lowercased() }
        let toAdd = short.filter { !store.onShoppingList($0.0, today) }
        NavigationStack {
            List {
                Section(header: Text("🛒  To buy for this plan")) {
                    if plan.isEmpty {
                        Text("Nothing planned yet.").foregroundColor(.secondary)
                    } else if short.isEmpty {
                        Text("✅  You have enough for everything planned.")
                    } else {
                        ForEach(short, id: \.0.id) { item, by in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.name)
                                    Text("Short by \(item.unitValue.label(by)) · planned \(WeekPlan.times(plan, item.id))×, have \(item.unitValue.label(store.stock(item.id)))")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                if store.onShoppingList(item, today) {
                                    Text("On list ✓").font(.caption)
                                } else {
                                    Button("Add to shop") { store.setRefillNeeded(item.id, true) }.buttonStyle(.borderless)
                                }
                            }
                        }
                        if toAdd.count > 1 {
                            Button("Add all \(toAdd.count) to shopping list") {
                                for (item, _) in toAdd { store.setRefillNeeded(item.id, true) }
                            }
                        }
                    }
                }
                ForEach(0..<7, id: \.self) { offset in
                    let epoch = today.epochDay + offset
                    let ids = plan[epoch] ?? []
                    Section {
                        if ids.isEmpty {
                            Text("Nothing planned").font(.caption).foregroundColor(.secondary)
                        }
                        ForEach(ids, id: \.self) { id in
                            if let item = store.item(id) {
                                HStack {
                                    Text(item.name)
                                    Spacer()
                                    Button { store.planToggle(epoch, id) } label: { Image(systemName: "xmark.circle.fill").foregroundColor(.secondary) }
                                        .buttonStyle(.borderless).accessibilityLabel("Remove \(item.name)")
                                }
                            }
                        }
                        Button("＋ Add") { picking = epoch }
                    } header: {
                        Text(title(epoch))
                    }
                }
            }
            .navigationTitle("Week agenda")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if !plan.isEmpty { Button("Clear") { store.weekPlan = [:] } }
                }
            }
            .sheet(item: Binding(get: { picking.map { PickDay(epoch: $0) } }, set: { picking = $0?.epoch })) { day in
                PlanPicker(epoch: day.epoch, title: title(day.epoch))
            }
        }
    }
}

private struct PickDay: Identifiable { let epoch: Int; var id: Int { epoch } }

private struct PlanPicker: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let epoch: Int
    let title: String
    @State private var query = ""

    var body: some View {
        let chosen = store.weekPlan[epoch] ?? []
        let list = store.items.filter { query.isEmpty || $0.name.lowercased().contains(query.lowercased()) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
        NavigationStack {
            List(list) { item in
                Button { store.planToggle(epoch, item.id) } label: {
                    HStack {
                        Image(systemName: chosen.contains(item.id) ? "checkmark.circle.fill" : "circle")
                        Text(item.name).foregroundColor(.primary)
                        Spacer()
                        Text(item.unitValue.label(store.stock(item.id))).font(.caption).foregroundColor(.secondary)
                    }
                }
            }
            .searchable(text: $query, prompt: "Search")
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
