import SwiftUI

/// What the Recipes tab is editing: a recipe, or a new one (id 0).
private struct RecipeTarget: Identifiable {
    let id: Int64
}

// MARK: - Recipes tab

struct RecipesView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @State private var onlyReady = false
    @State private var cooking: Recipe?
    @State private var editing: RecipeTarget?
    @State private var undo: UndoState?

    var body: some View {
        let rows = store.recipes.map { ($0, Recipes.status($0.lines, stockOf: { store.stock($0) })) }
        let shown = rows
            .filter { !onlyReady || $0.1 != .missing }
            .sorted { a, b in a.1.rawValue != b.1.rawValue ? a.1.rawValue < b.1.rawValue : a.0.name.lowercased() < b.0.name.lowercased() }
        let planned = store.recipes.filter { $0.plannedDay != Recipes.notPlanned }.count

        NavigationStack {
            List {
                if store.recipes.isEmpty {
                    VStack(spacing: 10) {
                        Text("🍲").font(.system(size: 44))
                        Text("No recipes yet").font(.headline)
                        Text("Tap ＋ to add a dish, like Chole. Pick what goes in from your pantry. Then cooking it takes the right amounts off your stock.")
                            .font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                } else {
                    Section {
                        Toggle("What can I cook?", isOn: $onlyReady)
                        if planned > 0 { Text("\(planned) planned").font(.subheadline).foregroundColor(.secondary) }
                    }
                    if shown.isEmpty {
                        Text("Nothing is fully in stock right now. Turn the filter off to see everything.").font(.caption).foregroundColor(.secondary)
                    }
                    ForEach(shown, id: \.0.id) { recipe, status in
                        card(recipe, status)
                    }
                }
            }
            .navigationTitle("Recipes")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { editing = RecipeTarget(id: 0) } label: { Image(systemName: "plus") }
                        .accessibilityLabel("Add recipe")
                }
            }
            .sheet(item: $cooking) { recipe in
                CookRecipeSheet(recipe: recipe) { result in
                    if let result = result { showUndo($undo, result.0, result.1) }
                }
            }
            .sheet(item: $editing) { target in EditRecipeView(recipeId: target.id, today: today) }
            .undoOverlay($undo)
        }
    }

    private func card(_ recipe: Recipe, _ status: RecipeStatus) -> some View {
        let missingNeeded = Recipes.missing(recipe.lines, stockOf: { store.stock($0) }, needed: true).compactMap { store.item($0.itemId)?.name }
        let missingOptional = Recipes.missing(recipe.lines, stockOf: { store.stock($0) }, needed: false).compactMap { store.item($0.itemId)?.name }
        let cost = Recipes.cost(recipe.lines) { Recipes.unitPriceMinor(store.entries($0)) }
        let pill: (String, StockStatus) = {
            switch status {
            case .ready: return ("Can cook now", .ok)
            case .missingOptional: return ("Can cook · missing " + missingOptional.joined(separator: ", "), .low)
            case .missing: return ("Missing " + missingNeeded.joined(separator: ", "), .out)
            }
        }()
        var parts = ["\(recipe.mealsAlone) meals, or \(recipe.mealsShared) shared"]
        if let cost = cost { parts.append("about \(Money.format(cost / Int64(max(1, recipe.mealsAlone)), store.currency)) a meal") }
        if recipe.plannedDay != Recipes.notPlanned { parts.append("🍳 \(Recipes.planLabel(recipe.plannedDay, today))") }
        return HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(recipe.name).font(.headline).lineLimit(1)
                StockPill(text: pill.0, status: pill.1)
                Text(parts.joined(separator: " · ")).font(.caption).foregroundColor(.secondary)
            }
            Spacer(minLength: 4)
            Button { editing = RecipeTarget(id: recipe.id) } label: { Image(systemName: "pencil") }
                .buttonStyle(.borderless)
                .accessibilityLabel("Edit \(recipe.name)")
        }
        .contentShape(Rectangle())
        .onTapGesture { cooking = recipe }
    }
}

// MARK: - Cook a recipe

/// Shows each ingredient with its usual amount, which you can change with 25 / 50 / 75 / All of what's left,
/// or by typing. Then the stock goes down by those amounts.
struct CookRecipeSheet: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let recipe: Recipe
    let onDone: ((String, () -> Void)?) -> Void

    @State private var shared = false
    @State private var included = Set<Int64>()
    @State private var texts: [Int64: String] = [:]
    @State private var ready = false

    private var lines: [RecipeLine] { recipe.lines.filter { store.item($0.itemId) != nil } }

    private func amount(_ line: RecipeLine) -> Double? {
        texts[line.itemId].flatMap { Qty.parse($0) }
    }

    private var picked: [RecipeLine] { lines.filter { included.contains($0.itemId) } }
    private var invalid: Bool { picked.contains { (amount($0) ?? 0) <= 0 } }

    private func setup() {
        guard !ready else { return }
        ready = true
        for line in lines {
            guard let item = store.item(line.itemId) else { continue }
            let stock = store.stock(line.itemId)
            let start = line.amount > 0 ? min(line.amount, stock) : min(item.step, stock)
            texts[line.itemId] = Qty.format(Qty.round(start))
            if stock > 0 && (line.needed || Recipes.has(line, stock)) { included.insert(line.itemId) }
        }
    }

    var body: some View {
        let missingNeeded = Recipes.missing(lines, stockOf: { store.stock($0) }, needed: true).compactMap { store.item($0.itemId) }
        NavigationStack {
            Form {
                Section {
                    Picker("Who's eating", selection: $shared) {
                        Text("Just me · \(recipe.mealsAlone) meals").tag(false)
                        Text("With friends · \(recipe.mealsShared) meals").tag(true)
                    }
                    .pickerStyle(.segmented)
                }
                if lines.isEmpty {
                    Text("This recipe has no ingredients yet. Tap the pencil on the card to add some.").font(.caption).foregroundColor(.secondary)
                } else {
                    if !missingNeeded.isEmpty {
                        Section {
                            Text("Missing: " + missingNeeded.map(\.name).joined(separator: ", ")).font(.caption).foregroundColor(.red)
                            Button("Add missing to buy") {
                                store.addMissingToBuy(missingNeeded)
                                dismiss()
                                onDone(nil)
                            }
                        }
                    }
                    Section(footer: Text("Change how much goes in. The stock goes down by these amounts.")) {
                        ForEach(lines, id: \.itemId) { line in ingredientRow(line) }
                    }
                }
            }
            .navigationTitle("🍳  \(recipe.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Cooked") {
                        let amounts: [(Item, Double)] = picked.compactMap { l in store.item(l.itemId).map { ($0, amount(l) ?? 0) } }
                        let result = store.cookRecipe(recipe, amounts: amounts, shared: shared)
                        dismiss()
                        onDone(result)
                    }
                    .disabled(picked.isEmpty || invalid)
                }
            }
            .onAppear(perform: setup)
        }
    }

    private func ingredientRow(_ line: RecipeLine) -> some View {
        let item = store.item(line.itemId)!
        let stock = store.stock(line.itemId)
        let empty = stock <= 0
        let on = included.contains(line.itemId)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Button {
                    if on { included.remove(line.itemId) } else { included.insert(line.itemId) }
                } label: {
                    Image(systemName: on ? "checkmark.circle.fill" : "circle").font(.title2)
                        .foregroundColor(on ? .accentColor : .secondary)
                }
                .buttonStyle(.borderless)
                .disabled(empty)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name + (line.needed ? "" : " (optional)")).font(.body).lineLimit(1)
                    Text(empty ? "None left" : "\(item.unitValue.label(stock)) left").font(.caption)
                        .foregroundColor(empty && line.needed ? .red : .secondary)
                }
                Spacer(minLength: 4)
                TextField("0", text: Binding(
                    get: { texts[line.itemId] ?? "" },
                    set: { texts[line.itemId] = $0; included.insert(line.itemId) }
                ))
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 70)
                .textFieldStyle(.roundedBorder)
                .disabled(empty)
            }
            if !empty {
                HStack(spacing: 6) {
                    ForEach([(0.25, "25%"), (0.5, "50%"), (0.75, "75%"), (1.0, "All")], id: \.1) { part, label in
                        Button(label) {
                            texts[line.itemId] = Qty.format(Qty.round(stock * part))
                            included.insert(line.itemId)
                        }
                        .buttonStyle(.bordered).controlSize(.mini)
                    }
                }
                .padding(.leading, 40)
            }
        }
    }
}

// MARK: - Recipe editor

struct EditRecipeView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let recipeId: Int64
    let today: Day

    @State private var name = ""
    @State private var alone = "4"
    @State private var shared = "2"
    @State private var plan = Recipes.notPlanned
    @State private var lines: [RecipeLine] = []
    @State private var amountTexts: [Int64: String] = [:]
    @State private var loaded = false
    @State private var picking = false
    @State private var confirmDelete = false
    @State private var showErrors = false

    private var aloneValue: Int? { Int(alone).flatMap { (1...99).contains($0) ? $0 : nil } }
    private var sharedValue: Int? { Int(shared).flatMap { (1...99).contains($0) ? $0 : nil } }
    private var nameError: Bool { name.trimmingCharacters(in: .whitespaces).isEmpty }
    private var amountsOk: Bool {
        lines.allSatisfy { l in
            let t = (amountTexts[l.itemId] ?? "").trimmingCharacters(in: .whitespaces)
            return t.isEmpty || Qty.parse(t) != nil
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let r = store.recipes.first(where: { $0.id == recipeId }) else { return }
        name = r.name
        alone = String(r.mealsAlone)
        shared = String(r.mealsShared)
        plan = r.plannedDay
        lines = r.lines
        for l in r.lines { amountTexts[l.itemId] = l.amount > 0 ? Qty.format(l.amount) : "" }
    }

    private func save() {
        guard !nameError, let a = aloneValue, let s = sharedValue, amountsOk else { showErrors = true; return }
        let saved = lines.map { l -> RecipeLine in
            var copy = l
            let t = (amountTexts[l.itemId] ?? "").trimmingCharacters(in: .whitespaces)
            copy.amount = t.isEmpty ? 0 : (Qty.parse(t) ?? 0)
            return copy
        }
        store.saveRecipe(Recipe(id: recipeId, name: String(name.trimmingCharacters(in: .whitespaces).prefix(60)), mealsAlone: a, mealsShared: s, lines: saved, plannedDay: plan))
        dismiss()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Recipe name, e.g. Chole", text: $name)
                        .textInputAutocapitalization(.words)
                    if showErrors && nameError { Text("Give it a name").font(.caption).foregroundColor(.red) }
                }
                Section(header: Text("One cook makes"), footer: Text("Either you eat it yourself and get \(alone) meals, or you share it and it makes \(shared).")) {
                    HStack {
                        Text("Meals for me")
                        Spacer()
                        TextField("4", text: $alone).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 60)
                    }
                    HStack {
                        Text("Meals if shared")
                        Spacer()
                        TextField("2", text: $shared).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 60)
                    }
                    if showErrors && (aloneValue == nil || sharedValue == nil) { Text("Use a number from 1 to 99").font(.caption).foregroundColor(.red) }
                }
                Section(header: Text("Ingredients"), footer: lines.isEmpty ? Text("Add what goes in, from your pantry. Mark things like garlic or lemon as optional.") : nil) {
                    ForEach(lines, id: \.itemId) { line in ingredientRow(line) }
                        .onDelete { offsets in lines.remove(atOffsets: offsets) }
                    Button("＋  Add ingredient") { picking = true }
                    if store.items.isEmpty { Text("Add items to your pantry first, then pick them here.").font(.caption).foregroundColor(.secondary) }
                }
                Section(header: Text("Plan it"), footer: Text("Planned meals put what's missing on To buy, with the reason. Not planned means nothing is added.")) {
                    Picker("When", selection: $plan) {
                        Text(Recipes.planLabel(Recipes.notPlanned, today)).tag(Recipes.notPlanned)
                        Text(Recipes.planLabel(Recipes.anyDay, today)).tag(Recipes.anyDay)
                        ForEach(0..<7, id: \.self) { n in
                            Text(Recipes.planLabel(today.epochDay + n, today)).tag(today.epochDay + n)
                        }
                    }
                }
                if recipeId != 0 {
                    Section {
                        Button("Delete recipe", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle(recipeId == 0 ? "New recipe" : "Edit recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save) }
            }
            .onAppear(perform: load)
            .sheet(isPresented: $picking) { IngredientPicker(taken: Set(lines.map(\.itemId))) { id in lines.append(RecipeLine(itemId: id)) } }
            .alert("Delete this recipe?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) { store.deleteRecipe(recipeId); dismiss() }
                Button("Keep", role: .cancel) {}
            } message: {
                Text("Your pantry stays as it is. Only the recipe goes.")
            }
        }
    }

    private func ingredientRow(_ line: RecipeLine) -> some View {
        let item = store.item(line.itemId)
        return VStack(alignment: .leading, spacing: 6) {
            Text(item?.name ?? "Removed item").font(.headline)
            Picker("", selection: Binding(
                get: { line.needed },
                set: { n in if let i = lines.firstIndex(where: { $0.itemId == line.itemId }) { lines[i].needed = n } }
            )) {
                Text("Needed").tag(true)
                Text("Optional").tag(false)
            }
            .pickerStyle(.segmented)
            if let item = item {
                HStack {
                    Text("Usual amount (\(item.unitValue.plural))").font(.subheadline).foregroundColor(.secondary)
                    Spacer()
                    TextField("a little", text: Binding(get: { amountTexts[line.itemId] ?? "" }, set: { amountTexts[line.itemId] = $0 }))
                        .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 90)
                }
            }
        }
    }
}

/// Pick a pantry item to add to a recipe.
private struct IngredientPicker: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let taken: Set<Int64>
    let onPick: (Int64) -> Void
    @State private var search = ""

    var body: some View {
        let available = store.items
            .filter { !taken.contains($0.id) && (search.isEmpty || $0.name.localizedCaseInsensitiveContains(search)) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
        NavigationStack {
            List {
                ForEach(available) { item in
                    Button {
                        onPick(item.id)
                        dismiss()
                    } label: {
                        HStack(spacing: 10) {
                            ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 36)
                            Text(item.name).foregroundColor(.primary)
                        }
                    }
                }
                if available.isEmpty { Text("No items found. Add it to your pantry first.").font(.caption).foregroundColor(.secondary) }
            }
            .searchable(text: $search)
            .navigationTitle("Add ingredient")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}
