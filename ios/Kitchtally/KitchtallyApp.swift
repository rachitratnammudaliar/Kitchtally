import PhotosUI
import SwiftUI

@main
struct KitchtallyApp: App {
    @StateObject private var store = Store()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .tint(Color(rgb: 0x2E6B3A))
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var store: Store
    @State private var today = Day.today()
    @State private var tab = 0
    private let timer = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some View {
        let plannedIds = Set(store.plannedNeeds().map { $0.item.id })
        let toBuy = Set(store.items.filter { store.onShoppingList($0, today) }.map(\.id)).union(plannedIds).count + store.notes.count
        TabView(selection: $tab) {
            PantryView(today: today, tab: $tab)
                .tabItem { Label("Pantry", systemImage: "house") }
                .tag(0)
            RecipesView(today: today)
                .tabItem { Label("Recipes", systemImage: "fork.knife") }
                .tag(3)
            ShoppingView(today: today)
                .tabItem { Label("To buy", systemImage: "cart") }
                .badge(toBuy)
                .tag(1)
            WeekView(today: today)
                .tabItem { Label("Weekly", systemImage: "chart.bar") }
                .tag(2)
        }
        .onReceive(timer) { _ in today = Day.today() }
        .alert("Kitchtally", isPresented: Binding(get: { store.problem != nil }, set: { if !$0 { store.problem = nil } })) {
            Button("OK", role: .cancel) { store.problem = nil }
        } message: {
            Text(store.problem ?? "")
        }
    }
}

// MARK: - Shared pieces

extension Color {
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

/// The item's photo, or its category's picture on a soft tile.
struct ItemImage: View {
    let name: String
    var photoFile: String = ""
    var category: String? = nil
    var size: CGFloat = 48

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.25, style: .continuous)
        if let image = PhotoStore.thumbnail(photoFile, size: size) {
            Image(uiImage: image).resizable().scaledToFill().frame(width: size, height: size).clipShape(shape)
        } else if let category = category {
            ZStack {
                shape.fill(Color(rgb: Badges.colorFor(name.isEmpty ? "?" : name)).opacity(0.16))
                Text(Categories.emojiFor(category)).font(.system(size: size * 0.5))
            }
            .frame(width: size, height: size)
        } else {
            ZStack {
                shape.fill(Color(rgb: Badges.colorFor(name.isEmpty ? "?" : name)))
                Text(Badges.initial(name)).font(.system(size: size * 0.42, weight: .bold)).foregroundColor(.white)
            }
            .frame(width: size, height: size)
        }
    }
}

struct StockPill: View {
    let text: String
    let status: StockStatus
    var onTap: (() -> Void)? = nil

    var body: some View {
        let colors: (Color, Color) = {
            switch status {
            case .out, .last: return (Color.red.opacity(0.15), .red)
            case .low: return (Color.orange.opacity(0.18), Color(rgb: 0x9A5B00))
            case .ok: return (Color(rgb: 0x2E6B3A).opacity(0.14), Color(rgb: 0x2E6B3A))
            }
        }()
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundColor(colors.1)
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .background(Capsule().fill(colors.0))
            .contentShape(Capsule())
            .onTapGesture { onTap?() }
            .accessibilityAddTraits(onTap == nil ? [] : .isButton)
    }
}

/// Text colour for an "almost out" or "last meal" line.
func statusColor(_ status: StockStatus) -> Color {
    switch status {
    case .out, .last: return .red
    case .low: return Color(rgb: 0x9A5B00)
    case .ok: return .secondary
    }
}

struct StatTile: View {
    let value: String
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.weight(.bold)).lineLimit(1)
            Text(label).font(.caption).foregroundColor(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(.systemBackground).opacity(0.6)))
    }
}

struct InfoRow: View {
    let label: String
    let value: String
    var note: String = ""

    var body: some View {
        HStack(alignment: .top) {
            Text(label).foregroundColor(.secondary)
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(value).fontWeight(.medium).multilineTextAlignment(.trailing)
                if !note.isEmpty { Text(note).font(.caption).foregroundColor(.secondary).multilineTextAlignment(.trailing) }
            }
        }
    }
}

/// A short message with an Undo button that hides itself.
struct UndoToast: View {
    let message: String
    let onUndo: () -> Void

    var body: some View {
        HStack {
            Text(message).font(.subheadline).foregroundColor(.white).lineLimit(2)
            Spacer()
            Button("Undo", action: onUndo).font(.subheadline.weight(.semibold)).foregroundColor(Color(rgb: 0x9BD69B))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.black.opacity(0.85)))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

struct UndoState: Equatable {
    let id = UUID()
    let message: String
    let undo: () -> Void

    static func == (a: UndoState, b: UndoState) -> Bool { a.id == b.id }
}

/// Shows an undo message for four seconds.
@MainActor
func showUndo(_ state: Binding<UndoState?>, _ message: String, _ undo: @escaping () -> Void) {
    let new = UndoState(message: message, undo: undo)
    withAnimation { state.wrappedValue = new }
    DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
        if state.wrappedValue == new { withAnimation { state.wrappedValue = nil } }
    }
}

extension View {
    /// The undo message at the bottom of a screen.
    func undoOverlay(_ state: Binding<UndoState?>) -> some View {
        overlay(alignment: .bottom) {
            if let u = state.wrappedValue {
                UndoToast(message: u.message) {
                    u.undo()
                    withAnimation { state.wrappedValue = nil }
                }
            }
        }
    }
}

/// The camera, for taking a photo of an item or a receipt.
struct CameraPicker: UIViewControllerRepresentable {
    let onPicked: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onPicked(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

/// "Take photo", "Choose" and "Remove". Calls [onPicked] with the stored file name.
struct PhotoButtons: View {
    let hasPhoto: Bool
    let onPicked: (String) -> Void
    let onRemove: () -> Void
    @State private var showCamera = false
    @State private var selection: PhotosPickerItem?

    var body: some View {
        HStack(spacing: 10) {
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button { showCamera = true } label: { Label("Take photo", systemImage: "camera") }
                    .buttonStyle(.bordered)
            }
            PhotosPicker(selection: $selection, matching: .images) { Label("Choose", systemImage: "photo") }
                .buttonStyle(.bordered)
            if hasPhoto {
                Button("Remove", role: .destructive, action: onRemove).buttonStyle(.borderless)
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                if let name = PhotoStore.save(image) { onPicked(name) }
            }
            .ignoresSafeArea()
        }
        .onChange(of: selection) { item in
            guard let item = item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
                   let name = PhotoStore.save(image) {
                    await MainActor.run { onPicked(name) }
                }
                await MainActor.run { selection = nil }
            }
        }
    }
}

/// Preset chips plus "Other", where you type your own (shops, categories).
struct NamedChoice: View {
    let options: [String]
    @Binding var selected: String
    let otherLabel: String
    @State private var typing = false
    @State private var custom = ""

    var body: some View {
        let all = selected.isEmpty || options.contains(where: { $0.lowercased() == selected.lowercased() }) ? options : options + [selected]
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(all, id: \.self) { option in
                        chip(option, isOn: !typing && option.lowercased() == selected.lowercased()) {
                            typing = false
                            selected = option.lowercased() == selected.lowercased() ? "" : option
                        }
                    }
                    chip("+ Other", isOn: typing) { typing = true }
                }
            }
            if typing {
                TextField(otherLabel, text: $custom)
                    .textInputAutocapitalization(.words)
                    .onChange(of: custom) { value in
                        let trimmed = String(value.prefix(40))
                        if trimmed != value { custom = trimmed }
                        selected = trimmed.trimmingCharacters(in: .whitespaces)
                    }
            }
        }
    }

    private func chip(_ title: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Capsule().fill(isOn ? Color(rgb: 0x2E6B3A).opacity(0.18) : Color(.secondarySystemFill)))
                .overlay(Capsule().stroke(isOn ? Color(rgb: 0x2E6B3A) : Color.clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
