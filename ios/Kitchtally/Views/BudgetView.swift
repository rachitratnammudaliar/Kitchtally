import SwiftUI

/// Money, month by month: everyday shopping and long-lasting things (face cream, toilet paper) apart.
struct BudgetView: View {
    @EnvironmentObject private var store: Store
    let today: Day
    @State private var offset = 0
    @State private var path: [Int64] = []

    private func monthLabel(_ first: Day) -> String {
        let f = DateFormatter()
        f.dateFormat = "LLLL yyyy"
        return f.string(from: first.localDate)
    }

    private func dayLabel(_ day: Day) -> String {
        let f = DateFormatter()
        f.dateFormat = "d MMM"
        return f.string(from: day.localDate)
    }

    private func comparison(_ now: Int64, _ before: Int64) -> String? {
        guard now > 0 || before > 0 else { return nil }
        let diff = now - before
        if diff > 0 { return "\(Money.format(diff, store.currency)) more than the month before" }
        if diff < 0 { return "\(Money.format(-diff, store.currency)) less than the month before" }
        return "Same as the month before"
    }

    @ViewBuilder
    private func bars(_ title: String, _ byCategory: [String: Int64]) -> some View {
        if !byCategory.isEmpty {
            Section(title) {
                let maxValue = byCategory.values.max() ?? 1
                ForEach(byCategory.keys.sorted { (byCategory[$0] ?? 0) > (byCategory[$1] ?? 0) }, id: \.self) { category in
                    let minor = byCategory[category] ?? 0
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("\(Categories.emojiFor(category))  \(category)")
                            Spacer()
                            Text(Money.format(minor, store.currency)).fontWeight(.medium)
                        }
                        ProgressView(value: Double(minor), total: Double(maxValue))
                    }
                }
            }
        }
    }

    var body: some View {
        let first = today.firstOfMonth.plusMonths(-offset)
        let byItem = store.entriesByItem
        let categoryOf: (Int64) -> String = { id in store.item(id)?.category ?? "Other" }
        let month = Pantry.month(first, byItem, categoryOf: categoryOf, isLong: { store.isLong($0) })
        let before = Pantry.month(first.plusMonths(-1), byItem, categoryOf: categoryOf, isLong: { store.isLong($0) })

        NavigationStack(path: $path) {
            List {
                Section {
                    HStack {
                        Button { offset += 1 } label: { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                        Spacer()
                        Text(monthLabel(first)).font(.headline)
                        Spacer()
                        Button { offset -= 1 } label: { Image(systemName: "chevron.right") }.buttonStyle(.borderless).disabled(offset == 0)
                    }
                }

                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Everyday shopping").font(.subheadline).foregroundColor(.secondary)
                        Text(Money.format(month.everydayMinor, store.currency)).font(.system(size: 30, weight: .semibold, design: .rounded))
                        if store.budgetMinor > 0 {
                            Text("of \(Money.format(store.budgetMinor, store.currency)) budget").font(.subheadline)
                            ProgressView(value: min(Double(month.everydayMinor), Double(store.budgetMinor)), total: Double(store.budgetMinor))
                            Text(month.everydayMinor <= store.budgetMinor ? "\(Money.format(store.budgetMinor - month.everydayMinor, store.currency)) left"
                                 : "\(Money.format(month.everydayMinor - store.budgetMinor, store.currency)) over")
                                .font(.caption).foregroundColor(.secondary)
                        }
                        if let c = comparison(month.everydayMinor, before.everydayMinor) { Text(c).font(.caption).foregroundColor(.secondary) }
                    }
                    .padding(.vertical, 6)
                    .listRowBackground(Color(rgb: 0x2E6B3A).opacity(0.12))
                }

                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Long-lasting things").font(.subheadline).foregroundColor(.secondary)
                        Text(Money.format(month.longMinor, store.currency)).font(.system(size: 30, weight: .semibold, design: .rounded))
                        Text("Face cream, toilet paper, shower gel and other non-food things that last a month or more. They are kept out of the weekly shop.")
                            .font(.caption).foregroundColor(.secondary)
                        if let c = comparison(month.longMinor, before.longMinor) { Text(c).font(.caption).foregroundColor(.secondary) }
                    }
                    .padding(.vertical, 6)
                }

                bars("Everyday, by category", month.everydayByCategory)
                bars("Long-lasting, by category", month.longByCategory)

                if !month.longBuys.isEmpty {
                    Section("Long-lasting purchases") {
                        ForEach(Array(month.longBuys.enumerated()), id: \.offset) { _, buy in
                            if let item = store.item(buy.itemId) {
                                HStack(spacing: 12) {
                                    ItemImage(name: item.name, photoFile: item.photoFile, category: item.category, size: 40)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.name).font(.subheadline.weight(.semibold))
                                        Text("Bought \(item.unitValue.label(buy.quantity)) · \(dayLabel(buy.date))").font(.caption).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    if buy.spentMinor > 0 { Text(Money.format(buy.spentMinor, store.currency)).font(.subheadline.weight(.medium)) }
                                }
                                .contentShape(Rectangle())
                                .onTapGesture { path.append(item.id) }
                            }
                        }
                    }
                }

                if month.everydayMinor == 0 && month.longMinor == 0 {
                    Text("No prices logged in \(monthLabel(first)). Add a price when you buy something and it shows up here.")
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Budget")
            .navigationDestination(for: Int64.self) { id in ItemView(id: id, today: today) }
        }
    }
}
