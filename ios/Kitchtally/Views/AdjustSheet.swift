import SwiftUI

/// Change any amount at once: −2, +2, or type a number, in pieces or in packs.
struct AdjustSheet: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let item: Item
    let onDone: (String, @escaping () -> Void) -> Void

    @State private var inPacks = false
    @State private var text = "1"

    private var value: Double? {
        guard let v = Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")), v >= 0, v <= 10_000 else { return nil }
        return v
    }
    private var amount: Double? { value.map { Qty.round(inPacks ? $0 * item.packSize : $0) } }

    private func step(_ by: Double) { text = Qty.format(max(0, Qty.round((value ?? 0) + by))) }

    private func run(_ action: Store.Adjust) {
        guard let a = amount else { return }
        if let result = store.adjust(item, action, a) { onDone(result.0, result.1) }
        dismiss()
    }

    var body: some View {
        if item.unitValue.isLevel {
            LevelSheet(item: item, onDone: onDone)
        } else {
            amountSheet
        }
    }

    private var amountSheet: some View {
        let unit = item.unitValue
        let stock = store.stock(item.id)
        let hint = Pantry.packsHint(stock, item.packSize)
        NavigationStack {
            Form {
                Section {
                    Text("Now: \(stockLabel(item, stock))" + (hint.isEmpty ? "" : " (\(hint))")).foregroundColor(.secondary)
                    if item.packSize > 0 {
                        Picker("Count in", selection: $inPacks) {
                            Text(unit.plural.capitalized).tag(false)
                            Text("Packs of \(Qty.format(item.packSize))").tag(true)
                        }
                        .pickerStyle(.segmented)
                    }
                    HStack(spacing: 8) {
                        Button("−2") { step(-2) }.buttonStyle(.bordered)
                        Button("−1") { step(-1) }.buttonStyle(.bordered)
                        TextField("1", text: $text).keyboardType(.decimalPad).multilineTextAlignment(.center)
                            .font(.title3.weight(.semibold)).frame(maxWidth: .infinity)
                        Button("+1") { step(1) }.buttonStyle(.bordered)
                        Button("+2") { step(2) }.buttonStyle(.bordered)
                    }
                    if inPacks, let a = amount { Text("= \(unit.label(a))").font(.caption).foregroundColor(.secondary) }
                }
                Section {
                    let ok = (amount ?? 0) > 0
                    HStack(spacing: 8) {
                        Button("Used") { run(.used) }.buttonStyle(.bordered).disabled(!ok || stock <= 0).frame(maxWidth: .infinity)
                        Button("Shared") { run(.shared) }.buttonStyle(.bordered).disabled(!ok || stock <= 0).frame(maxWidth: .infinity)
                        Button("Bought") { run(.bought) }.buttonStyle(.borderedProminent).disabled(!ok).frame(maxWidth: .infinity)
                    }
                    Button(amount.map { "Set what's left to \(unit.label($0))" } ?? "Set what's left") { run(.set) }
                        .buttonStyle(.borderless).disabled(amount == nil)
                } footer: {
                    Text("Used and Shared never take more than what's left.")
                }
            }
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// For % items: tap how full it is now, or type an exact %.
struct LevelSheet: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let item: Item
    let onDone: (String, @escaping () -> Void) -> Void
    @State private var text = ""

    private func run(_ action: Store.Adjust, _ amount: Double) {
        if let result = store.adjust(item, action, amount) { onDone(result.0, result.1) }
        dismiss()
    }

    var body: some View {
        let stock = store.stock(item.id)
        let percent = Int(text.trimmingCharacters(in: .whitespaces)).flatMap { (0...1000).contains($0) ? $0 : nil }
        NavigationStack {
            Form {
                Section {
                    Text("Now: \(stockLabel(item, stock))").foregroundColor(.secondary)
                    HStack(spacing: 6) {
                        ForEach([100, 75, 50, 25, 0], id: \.self) { p in
                            Button(p == 0 ? "Empty" : "\(p)%") { run(.set, Double(p) / 100) }
                                .buttonStyle(.bordered).frame(maxWidth: .infinity)
                        }
                    }
                    HStack {
                        TextField("Exact %", text: $text).keyboardType(.numberPad)
                        Button("Set") { run(.set, Double(percent ?? 0) / 100) }.disabled(percent == nil)
                    }
                } header: {
                    Text("How much is left?")
                } footer: {
                    Text("Over 100% means more than one: 150% is one full and one half.")
                }
                Section {
                    HStack(spacing: 8) {
                        Button("Used \(item.unitValue.label(item.step))") { run(.used, item.step) }
                            .buttonStyle(.bordered).disabled(stock <= 0).frame(maxWidth: .infinity)
                        Button("Bought a new one") { run(.bought, 1) }
                            .buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                    }
                }
            }
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .onAppear { text = "\(Qty.percent(max(0, stock)))" }
        }
        .presentationDetents([.medium, .large])
    }
}
