import XCTest
@testable import Kitchtally

/// Mirrors the Kotlin PantryTest and BackupTest so both apps calculate the same.
final class KitchtallyTests: XCTestCase {
    private let today = Day(2026, 11, 4) // a Wednesday
    private func d(_ daysAgo: Int) -> Day { today.plusDays(-daysAgo) }
    private func bought(_ daysAgo: Int, _ qty: Double = 1, _ price: Int64? = nil, _ shop: String = "") -> Entry {
        Entry(type: .BOUGHT, date: d(daysAgo), quantity: qty, priceMinor: price, shop: shop)
    }
    private func used(_ daysAgo: Int, _ qty: Double = 1) -> Entry { Entry(type: .USED, date: d(daysAgo), quantity: qty) }
    private func shared(_ daysAgo: Int, _ qty: Double = 1) -> Entry { Entry(type: .SHARED, date: d(daysAgo), quantity: qty) }

    func testStock() {
        let e = [Entry(type: .ADJUST, date: d(10), quantity: 2), bought(5, 2), used(3), shared(1, 0.5)]
        XCTAssertEqual(Pantry.stock(e), 2.5)
        XCTAssertEqual(Pantry.stock([]), 0)
        XCTAssertEqual(Pantry.stock([bought(1, 0.1), bought(1, 0.2), bought(1, 0.7)]), 1)
    }

    func testStatus() {
        XCTAssertEqual(Pantry.status([bought(5), used(1)], lowAt: 1, today), .out)
        XCTAssertEqual(Pantry.status([bought(5, 2), used(1)], lowAt: 1, today), .low)
        XCTAssertEqual(Pantry.status([bought(5, 3)], lowAt: 1, today), .ok)
        XCTAssertEqual(Pantry.status([bought(5, 1)], lowAt: 0, today), .ok)
    }

    func testDailyUseAndDaysLeft() {
        let e = [bought(10, 6), used(7), used(5), used(3), shared(1)]
        XCTAssertEqual(Pantry.dailyUse(e, today)!, 0.5, accuracy: 0.0001)
        XCTAssertEqual(Pantry.daysLeft(e, today), 4)
        XCTAssertNil(Pantry.dailyUse([bought(3), used(1)], today))
    }

    func testFreshness() {
        XCTAssertTrue(Pantry.checkFreshness([bought(6)], .SHORT, today))
        XCTAssertFalse(Pantry.checkFreshness([bought(4)], .SHORT, today))
        XCTAssertFalse(Pantry.checkFreshness([bought(60)], .LONG, today))
        XCTAssertFalse(Pantry.checkFreshness([bought(6), used(1)], .SHORT, today))
    }

    func testPrices() {
        XCTAssertEqual(Pantry.unitPriceMinor(bought(1, 5, 1245, "Maxima")), 249)
        XCTAssertEqual(Pantry.totalMinor(120, 1.5), 180)
        XCTAssertNil(Pantry.unitPriceMinor(bought(1)))
        let e = [bought(10, 1, 100, "IKI"), bought(5, 2, nil, "Lidl"), bought(2, 2, 300, "Norfa")]
        XCTAssertEqual(Pantry.lastPriced(e)?.shop, "Norfa")
        XCTAssertEqual(Pantry.lastBought(e)?.date, d(2))
    }

    func testWeek() {
        let start = Pantry.weekStart(today)
        XCTAssertEqual(start, Day(2026, 11, 2))
        XCTAssertEqual(Pantry.weekStart(Day(2026, 11, 8)), Day(2026, 11, 2))
        XCTAssertEqual(Pantry.weekStart(Day(2026, 11, 9)), Day(2026, 11, 9))
        let bread = [bought(9, 2, 178), bought(2, 2, 178, "IKI"), used(2), used(1), shared(0)]
        let rice = [bought(1, 5, 1245), used(1, 0.5)]
        let week = Pantry.week(start, [1: bread, 2: rice]) { $0 == 1 ? "Bread & bakery" : "Grains & flour" }
        let b = week.items.first { $0.itemId == 1 }!
        XCTAssertEqual(b.used, 3)
        XCTAssertEqual(b.shared, 1)
        XCTAssertEqual(b.bought, 2)
        XCTAssertEqual(b.spentMinor, 178)
        XCTAssertEqual(week.spentMinor, 1423)
        XCTAssertEqual(week.spentByCategory["Grains & flour"], 1245)
        XCTAssertEqual(week.usedTotal, 3.5)
        XCTAssertEqual(week.sharedTotal, 1)
        XCTAssertEqual(week.items.first?.itemId, 1)
        XCTAssertEqual(week.itemsUsed, 2)
        XCTAssertEqual(week.timesShared, 1)
        XCTAssertEqual(week.itemsBought, 2)
    }

    func testTapAmounts() {
        XCTAssertEqual(Pantry.defaultStep(.PACKET), 1)
        XCTAssertEqual(Pantry.defaultStep(.KG), 0.5)
        XCTAssertEqual(Pantry.useAmount(0.5, 4.5), 0.5)
        XCTAssertEqual(Pantry.useAmount(0.5, 0.3), 0.3)
        XCTAssertEqual(Pantry.useAmount(1, 0), 0)
        XCTAssertEqual(Pantry.useAmount(1, -2), 0)
        let rice = [bought(10, 5, 1245, "Maxima"), used(2, 0.5)]
        XCTAssertEqual(Pantry.buyAmount(.KG, rice), 5)
        XCTAssertEqual(Pantry.expectedPriceMinor(.KG, rice), 1245)
        let bread = [bought(3, 2, 178, "IKI")]
        XCTAssertEqual(Pantry.buyAmount(.PACKET, bread), 1)
        XCTAssertEqual(Pantry.expectedPriceMinor(.PACKET, bread), 89)
        XCTAssertEqual(Pantry.buyAmount(.KG, []), 1)
        XCTAssertNil(Pantry.expectedPriceMinor(.KG, [bought(1, 2)]))
    }

    func testPacks() {
        let eggs = [bought(5, 10, 289, "IKI"), used(3, 2)]
        XCTAssertEqual(Pantry.buyAmount(.PIECE, eggs, packSize: 10), 10)
        XCTAssertEqual(Pantry.expectedPriceMinor(.PIECE, eggs, packSize: 10), 289)
        XCTAssertEqual(Pantry.priceFor(eggs, 2), 58)
        XCTAssertEqual(Pantry.packLabel(.PIECE, 10), "1 pack = 10 pieces")
        XCTAssertEqual(Pantry.packsHint(8, 10), "about 0.8 packs")
        XCTAssertEqual(Pantry.packsHint(10, 10), "about 1 pack")
        XCTAssertEqual(Pantry.packsHint(25, 10), "about 3 packs")
    }

    func testQuantitiesMoneyAndShops() {
        XCTAssertEqual(Qty.format(1.5), "1.5")
        XCTAssertEqual(Qty.format(2), "2")
        XCTAssertEqual(Qty.approx(2.917), "3")
        XCTAssertEqual(Qty.approx(0.46), "0.5")
        XCTAssertEqual(Qty.parse("0,5"), 0.5)
        XCTAssertNil(Qty.parse("0"))
        XCTAssertEqual(Money.parseToMinor("1,2"), 120)
        XCTAssertEqual(Money.parseToMinor("0"), 0)
        XCTAssertNil(Money.parseToMinor("1.234"))
        XCTAssertNil(Money.parseToMinor("-1"))
        XCTAssertEqual(Money.minorToInput(120), "1.2")
        XCTAssertEqual(QtyUnit.PACKET.label(2), "2 packets")
        XCTAssertEqual(QtyUnit.PACKET.label(1), "1 packet")
        XCTAssertEqual(QtyUnit.KG.label(0.5), "0.5 kg")
        XCTAssertEqual(Shops.options(["iki", "Aibė", " ", "Aibė"]), ["IKI", "Norfa", "Maxima", "Lidl", "Rimi", "Pepco", "Aibė"])
        XCTAssertEqual(Categories.emojiFor("Bread & bakery"), "🍞")
        XCTAssertEqual(Categories.ordered(["Snacks", "Vegetables", "My stuff", "vegetables"]), ["Vegetables", "Snacks", "My stuff"])
        XCTAssertEqual(Badges.javaHash("hello"), 99162322)
    }

    func testNamesSuggestCategories() {
        let expected: [String: String] = [
            "Eggs": "Dairy & eggs", "Veggie mix": "Vegetables", "Pasta/spagheti": "Staples", "Chicken": "Meat & protein",
            "Bread": "Bread & bakery", "Milk /Substitute": "Dairy & eggs", "Red Onion": "Vegetables", "Potato": "Vegetables",
            "Carrot": "Vegetables", "Chole": "Staples", "Dishwashing liquid": "Household", "Tomato Puree": "Sauces & spreads",
            "Mushroom": "Vegetables", "Garlic Sauce": "Sauces & spreads", "Clever Yogurt": "Dairy & eggs", "Cheese": "Dairy & eggs",
            "Rice": "Staples", "Oil": "Staples", "Butter": "Dairy & eggs", "Salt": "Staples", "Sugar": "Staples",
            "Spices": "Spices", "Atta": "Staples", "Body wash": "Personal care", "Toilet paper": "Household",
            "Toothpaste": "Personal care", "Face wash": "Personal care", "Body cream": "Personal care", "Face cream": "Personal care",
            "Peanut butter": "Sauces & spreads", "Eggplant": "Vegetables", "Chickpeas": "Staples", "Hair oil": "Personal care",
            "Ice cream": "Snacks", "Apples": "Fruit",
        ]
        for (name, category) in expected { XCTAssertEqual(Suggestions.forName(name)?.category, category, name) }
        XCTAssertEqual(Suggestions.forName("Eggs")?.packSize, 10)
        XCTAssertEqual(Suggestions.forName("Eggs")?.unit, .PIECE)
        XCTAssertEqual(Suggestions.forName("Bread")?.life, .SHORT)
        XCTAssertNil(Suggestions.forName("Birthday candles"))
        XCTAssertEqual(Categories.renamed["Grains & flour"], "Staples")
    }

    func testBackupMatchesAndroidFormat() throws {
        let json = """
        {"format":"kitchtally-backup","version":1,"items":[
          {"ref":7,"name":"Basmati rice","category":"Grains & flour","unit":"KG","life":"LONG","lowAt":1.5,"refillNeeded":true,"notes":"5 kg bag","createdAt":1},
          {"ref":2,"name":"  "}
        ],"events":[
          {"item":7,"type":"BOUGHT","epochDay":20000,"quantity":5,"priceMinor":1245,"shop":"Maxima","note":"","createdAt":3},
          {"item":7,"type":"USED","epochDay":20001,"quantity":-1},
          {"item":9,"type":"USED","epochDay":20001,"quantity":1},
          {"item":7,"type":"USED","epochDay":20001,"quantity":0.5,"priceMinor":50}
        ]}
        """
        let c = try Backup.decode(Data(json.utf8))
        XCTAssertEqual(c.items.count, 1)
        XCTAssertEqual(c.items[0].lowAt, 1.5)
        XCTAssertEqual(c.items[0].category, "Staples")
        XCTAssertTrue(c.items[0].refillNeeded)
        XCTAssertEqual(c.events.count, 2)
        XCTAssertEqual(c.events[0].priceMinor, 1245)
        XCTAssertNil(c.events[1].priceMinor)

        XCTAssertEqual(c.items[0].step, 0.5)
        XCTAssertFalse(c.items[0].hiddenFromList)

        var items = c.items
        items[0].step = 0.25
        items[0].hiddenFromList = true
        items[0].packSize = 5
        items[0].unsure = true
        let again = try Backup.decode(Backup.encode(items, c.events, notes: [ShoppingNote(text: "Candles", createdAt: 9)]))
        XCTAssertEqual(again.items, items)
        XCTAssertEqual(again.notes.map(\.text), ["Candles"])
        XCTAssertEqual(again.events.map(\.quantity), [5, 0.5])
        XCTAssertThrowsError(try Backup.decode(Data("nope".utf8)))
    }
}
