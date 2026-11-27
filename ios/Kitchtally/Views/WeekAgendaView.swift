import SwiftUI

/// Plan the next 7 days from your pantry. Whatever you won't have enough of goes on the shopping list in one tap.
struct WeekAgendaView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let today: Day
    @State private var picking: Int? = nil
    @State private var pickingMeal = false
    @State private var editingDish: Int64? = nil

    private var plan: [Int: [Int64]] { WeekPlan.upcoming(store.weekPlan, today: today.epochDay) }

    private func shortList(plan: [Int: [Int64]], planned: [PlannedMeal]) -> [(Item, Double)] {
        let dayShort: [(Item, Double)] = Array(Set(plan.values.flatMap { $0 })).compactMap { id in
            guard let item = store.item(id) else { return nil }
            let by = WeekPlan.shortBy(plan, id, step: item.step, stock: store.stock(id))
            return by > 0 ? (item, by) : nil
        }
        let mealShort: [(Item, Double)] = MealPlan.shopping(planned.compactMap { m in store.dishes.first { $0.id == m.dishId } }, isOk: { store.ingredientOk($0, today) })
            .compactMap { id in store.item(id).map { ($0, max($0.step, 0.001)) } }
        var merged: [Int64: (Item, Double)] = [:]
        for (item, by) in dayShort + mealShort { merged[item.id] = (item, max(merged[item.id]?.1 ?? 0, by)) }
        return merged.values.sorted { $0.0.name.lowercased() < $1.0.name.lowercased() }
    }

    private func mealDay(_ day: Int) -> String {
        if day <= 0 { return "This week" }
        if day == today.epochDay { return "Today" }
        if day == today.epochDay + 1 { return "Tomorrow" }
        let f = DateFormatter(); f.dateFormat = "EEEE"
        return f.string(from: Day(epochDay: day).localDate)
    }

    private func title(_ epoch: Int) -> String {
        let f = DateFormatter()
        f.dateFormat = "EEEE d MMM"
        return f.string(from: Day(epochDay: epoch).localDate) + (epoch == today.epochDay ? " · Today" : "")
    }

    var body: some View {
        let plan = self.plan
        let planned = MealPlan.upcoming(store.plannedMeals, today: today.epochDay)
        let short = shortList(plan: plan, planned: planned)
        let week = Pantry.week(Pantry.weekStart(today, first: store.weekStartDay, anchor: store.weekAnchor), store.entriesByItem, categoryOf: { id in store.item(id)?.category ?? "" }, isLong: { store.isLong($0) })
        let estimate = short.reduce(Int64(0)) { $0 + WeeklyBudget.estimateMinor(unitPrice: Recipes.unitPriceMinor(store.entries($1.0.id)), shortBy: $1.1) }
        let unpriced = short.filter { Recipes.unitPriceMinor(store.entries($0.0.id)) == nil }.count
        let toAdd = short.filter { !store.onShoppingList($0.0, today) }
        NavigationStack {
            List {
                Section(header: Text("🛒  To buy for this plan")) {
                    if plan.isEmpty && planned.isEmpty {
                        Text("Nothing planned yet.").foregroundColor(.secondary)
                    } else if short.isEmpty {
                        Text("✅  You have enough for everything planned.")
                    } else {
                        ForEach(short, id: \.0.id) { item, by in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.name)
                                    Text(WeekPlan.times(plan, item.id) == 0 ? "Needed for your meals · have \(item.unitValue.label(store.stock(item.id)))"
                                         : "Short by \(item.unitValue.label(by)) · planned \(WeekPlan.times(plan, item.id))×, have \(item.unitValue.label(store.stock(item.id)))")
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
                        Text("Estimated cost: \(Money.format(estimate, store.currency))" + (unpriced > 0 ? " (+ \(unpriced) without a price yet)" : ""))
                            .fontWeight(.medium)
                        if store.weeklyBudgetMinor > 0 {
                            let left = store.weeklyBudgetMinor - week.spentMinor - estimate
                            Text(left >= 0 ? "Fits your \(Money.format(store.weeklyBudgetMinor, store.currency)) weekly budget, \(Money.format(left, store.currency)) to spare."
                                 : "\(Money.format(-left, store.currency)) over your \(Money.format(store.weeklyBudgetMinor, store.currency)) weekly budget. Drop something or plan cheaper.")
                                .font(.caption).foregroundColor(left >= 0 ? .secondary : .red)
                        }
                        if store.budgetMinor > 0 {
                            let month = Pantry.spentBetween(store.entriesByItem.filter { !store.isLong($0.key) }, today.firstOfMonth, today)
                            let mLeft = store.budgetMinor - month - estimate
                            Text(mLeft >= 0 ? "Month: \(Money.format(month, store.currency)) spent + this plan leaves \(Money.format(mLeft, store.currency)) of \(Money.format(store.budgetMinor, store.currency))."
                                 : "Month: this plan takes you \(Money.format(-mLeft, store.currency)) over \(Money.format(store.budgetMinor, store.currency)).")
                                .font(.caption).foregroundColor(mLeft >= 0 ? .secondary : .red)
                        }
                        if toAdd.count > 1 {
                            Button("Add all \(toAdd.count) to shopping list") {
                                for (item, _) in toAdd { store.setRefillNeeded(item.id, true) }
                            }
                        }
                    }
                }
                Section(header: Text("🍲  Meals this week")) {
                    if planned.isEmpty {
                        Text("No meals planned. Add the dishes you'll cook and what each needs.").foregroundColor(.secondary)
                    }
                    ForEach(planned.sorted { ($0.day > 0 ? $0.day : Int.max, $0.until) < ($1.day > 0 ? $1.day : Int.max, $1.until) }) { meal in
                        if let dish = store.dishes.first(where: { $0.id == meal.dishId }) {
                            let missing = dish.essentials.filter { !store.ingredientOk($0, today) }.compactMap { store.item($0)?.name }
                            HStack(spacing: 10) {
                                ItemImage(name: dish.name, photoFile: dish.photo, category: "", size: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(dish.name).fontWeight(.medium)
                                    Text(mealDay(meal.day) + " · " + (missing.isEmpty ? "✅ you have everything" : "Missing: " + missing.joined(separator: ", ")))
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                Button("Remove") { store.plannedMeals.removeAll { $0 == meal } }.buttonStyle(.borderless)
                            }
                        }
                    }
                    Button("＋ Meal") { pickingMeal = true }
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
            .onAppear { store.syncMealShopping(today) }
            .onChange(of: store.plannedMeals) { _ in store.syncMealShopping(today) }
            .onChange(of: store.dishes) { _ in store.syncMealShopping(today) }
            .navigationTitle("Week agenda")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if !plan.isEmpty || !planned.isEmpty { Button("Clear") { store.weekPlan = [:]; store.plannedMeals = [] } }
                }
            }
            .sheet(isPresented: $pickingMeal) {
                DishPicker(today: today, onEdit: { editingDish = $0 })
                    .sheet(item: Binding(get: { editingDish.map { EditDish(id: $0) } }, set: { editingDish = $0?.id })) { d in
                        DishEditor(dishId: d.id, today: today) { editingDish = nil; pickingMeal = false }
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

private struct EditDish: Identifiable { let id: Int64 }

/// Pick a saved dish to plan, choose its day, or start a new dish.
private struct DishPicker: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let today: Day
    let onEdit: (Int64) -> Void
    @State private var day = 0

    var body: some View {
        NavigationStack {
            List {
                Section(header: Text("Day")) {
                    Picker("Day", selection: $day) {
                        Text("Any day").tag(0)
                        ForEach(0..<7, id: \.self) { o in
                            let f: DateFormatter = { let f = DateFormatter(); f.dateFormat = "EEE"; return f }()
                            Text(f.string(from: Day(epochDay: today.epochDay + o).localDate)).tag(today.epochDay + o)
                        }
                    }
                    .pickerStyle(.menu)
                }
                let planned = Set(MealPlan.upcoming(store.plannedMeals, today: today.epochDay).map(\.dishId))
                let fresh = MealPlan.lastWeek(store.plannedMeals, today: today.epochDay).filter { !planned.contains($0) }.compactMap { id in store.dishes.first { $0.id == id } }
                if !fresh.isEmpty {
                    Section {
                        Button("Same as last week (\(fresh.count) \(fresh.count == 1 ? "meal" : "meals"))") {
                            for d in fresh { store.planMeal(d.id, day: 0, today: today) }
                            dismiss()
                        }
                        Text("Last week: " + fresh.map(\.name).joined(separator: ", ")).font(.caption).foregroundColor(.secondary)
                    }
                }
                Section(header: Text("Saved dishes")) {
                    if store.dishes.isEmpty { Text("No saved dishes yet. Create your first one.").foregroundColor(.secondary) }
                    ForEach(store.dishes.sorted { $0.name.lowercased() < $1.name.lowercased() }) { dish in
                        HStack {
                            Button { store.planMeal(dish.id, day: day, today: today); dismiss() } label: {
                                HStack(spacing: 10) {
                                    ItemImage(name: dish.name, photoFile: dish.photo, category: "", size: 34)
                                    Text(dish.name).foregroundColor(.primary)
                                }
                            }
                            Spacer()
                            Button("Edit") { onEdit(dish.id) }.buttonStyle(.borderless)
                        }
                    }
                    Button("＋ New dish") { onEdit(0) }
                }
            }
            .navigationTitle("Add a meal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}

/// Full-screen dish page: photo, name, and the ingredients grouped by what you still need to buy.
/// Each ingredient is either needed or optional; only needed ones that are out or low go on the shopping list.
private struct DishEditor: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let dishId: Int64
    let today: Day
    let done: () -> Void
    @State private var name = ""
    @State private var photo = ""
    @State private var originalPhoto = ""
    @State private var chosen: [Int64] = []
    @State private var optional: [Int64] = []
    @State private var query = ""
    @State private var loaded = false

    private func leave() {
        if !photo.isEmpty && photo != originalPhoto { PhotoStore.delete(photo) }
        done()
    }

    private func row(_ item: Item) -> some View {
        let ok = store.ingredientOk(item.id, today)
        let isOptional = optional.contains(item.id)
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                Text(store.stock(item.id) <= 0 ? "Out of stock" : (!ok ? "Running low · \(item.unitValue.label(store.stock(item.id)))" : "Have \(item.unitValue.label(store.stock(item.id)))"))
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Button(isOptional ? "Optional" : "Needed") {
                if let i = optional.firstIndex(of: item.id) { optional.remove(at: i) } else { optional.append(item.id) }
            }
            .buttonStyle(.bordered)
            Button { chosen.removeAll { $0 == item.id }; optional.removeAll { $0 == item.id } } label: { Image(systemName: "xmark.circle.fill").foregroundColor(.secondary) }
                .buttonStyle(.borderless)
        }
    }

    var body: some View {
        let existing = store.dishes.first { $0.id == dishId }
        let q = query.trimmingCharacters(in: .whitespaces)
        let chosenItems = chosen.compactMap { store.item($0) }
        let sorted: ([Item]) -> [Item] = { $0.sorted { $0.name.lowercased() < $1.name.lowercased() } }
        let toBuy = sorted(chosenItems.filter { !optional.contains($0.id) && !store.ingredientOk($0.id, today) })
        let have = sorted(chosenItems.filter { !optional.contains($0.id) && store.ingredientOk($0.id, today) })
        let optionalItems = sorted(chosenItems.filter { optional.contains($0.id) })
        let addable = sorted(store.items.filter { !chosen.contains($0.id) && (q.isEmpty || $0.name.lowercased().contains(q.lowercased())) })
        let canCreate = !q.isEmpty && !store.items.contains { $0.name.lowercased() == q.lowercased() }
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        ItemImage(name: name.isEmpty ? "Dish" : name, photoFile: photo, category: "", size: 64)
                        PhotoButtons(hasPhoto: !photo.isEmpty, onPicked: { newFile in
                            if !photo.isEmpty && photo != originalPhoto { PhotoStore.delete(photo) }
                            photo = newFile
                        }, onRemove: {
                            if photo != originalPhoto { PhotoStore.delete(photo) }
                            photo = ""
                        })
                    }
                    TextField("Dish, e.g. Egg curry", text: $name)
                }
                if !toBuy.isEmpty {
                    Section(header: Text("🛒  To buy · goes on your shopping list")) { ForEach(toBuy) { row($0) } }
                }
                if !have.isEmpty {
                    Section(header: Text("✅  You have")) { ForEach(have) { row($0) } }
                }
                if !optionalItems.isEmpty {
                    Section(header: Text("➕  Optional · never added to the list")) { ForEach(optionalItems) { row($0) } }
                }
                Section(header: Text("Add ingredients")) {
                    TextField("Search, or type a new one", text: $query)
                    if canCreate {
                        Button("＋ Add \"\(q)\" as a new ingredient") {
                            chosen.append(store.saveItem(Item(name: q)))
                            query = ""
                        }
                    }
                    ForEach(addable) { item in
                        Button { chosen.append(item.id) } label: {
                            HStack {
                                Text(item.name).foregroundColor(.primary)
                                Spacer()
                                Text(store.stock(item.id) <= 0 ? "❌ out" : (!store.ingredientOk(item.id, today) ? "⚠️ low" : "✅ have")).font(.caption).foregroundColor(.secondary)
                                Image(systemName: "plus.circle").foregroundColor(.accentColor)
                            }
                        }
                    }
                }
                if existing != nil {
                    Section { Button("Delete dish", role: .destructive) { store.deleteDish(dishId); done() } }
                }
            }
            .navigationTitle(existing == nil ? "New dish" : "Edit dish")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { leave() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    if !originalPhoto.isEmpty && photo != originalPhoto { PhotoStore.delete(originalPhoto) }
                    let id = store.saveDish(dishId, name: name, ingredients: chosen, optional: optional, photo: photo)
                    if existing == nil { store.planMeal(id, day: 0, today: today) }
                    done()
                } label: {
                    Text((existing == nil ? "Save and plan" : "Save") + (toBuy.isEmpty ? "" : " · \(toBuy.count) to buy")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                .padding()
                .background(.bar)
            }
            .onAppear {
                if !loaded, let d = existing { name = d.name; photo = d.photo; originalPhoto = d.photo; chosen = d.ingredients; optional = d.optional }
                loaded = true
            }
        }
    }
}
