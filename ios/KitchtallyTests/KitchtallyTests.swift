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
        let fast = [bought(6, 4), used(5, 1), used(3, 1), used(1, 1)]
        XCTAssertEqual(Pantry.status(fast, lowAt: 0.5, today), .low)
        XCTAssertEqual(Pantry.status(fast, lowAt: 0, today), .ok)
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
        XCTAssertFalse(Pantry.checkFreshness([bought(3)], .SHORT, today))
        XCTAssertTrue(Pantry.checkFreshness([bought(11)], .MEDIUM, today))
        XCTAssertFalse(Pantry.checkFreshness([bought(10)], .MEDIUM, today))
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

    func testAShopForNextWeekCountsInNextWeek() {
        let start = Pantry.weekStart(today)
        let next = start.plusDays(7)
        let forNext = Entry(type: .BOUGHT, date: start.plusDays(6), quantity: 2, priceMinor: 300, shop: "IKI", countsFrom: next)
        let usedNow = Entry(type: .USED, date: start.plusDays(6), quantity: 1)
        let items: [Int64: [Entry]] = [1: [forNext, usedNow]]
        let thisWeek = Pantry.week(start, items) { _ in "Other" }
        let nextWeek = Pantry.week(next, items) { _ in "Other" }
        XCTAssertEqual(thisWeek.itemsBought, 0)
        XCTAssertEqual(thisWeek.spentMinor, 0)
        XCTAssertEqual(thisWeek.itemsUsed, 1)
        XCTAssertEqual(nextWeek.itemsBought, 1)
        XCTAssertEqual(nextWeek.spentMinor, 300)
        XCTAssertEqual(Pantry.stock([forNext, usedNow]), 1)
    }

    func testLongLastingThingsAreKeptOutOfTheWeeklyShop() {
        let start = Pantry.weekStart(today)
        let cream = [Entry(type: .BOUGHT, date: start.plusDays(1), quantity: 1, priceMinor: 1200, shop: "Maxima")]
        let milk = [Entry(type: .BOUGHT, date: start.plusDays(1), quantity: 1, priceMinor: 85, shop: "IKI"), Entry(type: .USED, date: start.plusDays(2), quantity: 0.5)]
        let items: [Int64: [Entry]] = [1: cream, 2: milk]
        let week = Pantry.week(start, items, categoryOf: { _ in "Other" }, isLong: { $0 == 1 })
        XCTAssertEqual(week.spentMinor, 85)
        XCTAssertEqual(week.itemsBought, 1)
        XCTAssertEqual(week.longItems.count, 1)
        XCTAssertEqual(week.longSpentMinor, 1200)
        let m = Pantry.month(start.firstOfMonth, items, categoryOf: { $0 == 1 ? "Personal care" : "Dairy & eggs" }, isLong: { $0 == 1 })
        XCTAssertEqual(m.everydayMinor, 85)
        XCTAssertEqual(m.longMinor, 1200)
        XCTAssertEqual(m.longByCategory["Personal care"], 1200)
        XCTAssertEqual(m.longBuys.count, 1)
        XCTAssertTrue(Categories.isLongLasting("Personal care"))
        XCTAssertTrue(Categories.isLongLasting("household"))
        XCTAssertFalse(Categories.isLongLasting("Staples"))
    }

    func testAmountsReadLikePeopleSayThem() {
        XCTAssertEqual(QtyUnit.PACKET.label(0.5), "½ packet")
        XCTAssertEqual(QtyUnit.PACKET.label(0.25), "¼ packet")
        XCTAssertEqual(QtyUnit.PACKET.label(0.75), "¾ packet")
        XCTAssertEqual(QtyUnit.PACKET.label(1.5), "1½ packets")
        XCTAssertEqual(QtyUnit.PIECE.label(0.1), "a little")
        XCTAssertEqual(QtyUnit.PACKET.label(2), "2 packets")
        XCTAssertEqual(QtyUnit.KG.label(0.5), "0.5 kg")
        XCTAssertEqual(QtyUnit.PACKET.leftLabel(0.5), "½ packet left")
    }

    func testStartingANewWeekMakesTodayDayOne() {
        let sunday = Day(2026, 11, 8)
        XCTAssertEqual(Pantry.weekStart(sunday), Day(2026, 11, 2))
        XCTAssertEqual(Pantry.weekStart(sunday, anchor: sunday), sunday)
        XCTAssertEqual(Pantry.weekStart(sunday.plusDays(6), anchor: sunday), sunday)
        XCTAssertEqual(Pantry.weekStart(sunday.plusDays(7), anchor: sunday), sunday.plusDays(7))
        XCTAssertEqual(Pantry.weekStart(Day(2026, 11, 7), anchor: sunday), Day(2026, 11, 2))
    }

    func testWeekCanStartOnAnotherDay() {
        XCTAssertEqual(Pantry.weekStart(today, first: 7), Day(2026, 11, 1))
        XCTAssertEqual(Pantry.weekStart(today), Day(2026, 11, 2))
        XCTAssertEqual(Pantry.weekStart(Day(2026, 11, 8), first: 6), Day(2026, 11, 7))
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

    func testPercentItems() {
        let u = QtyUnit.LEVEL
        XCTAssertEqual(u.label(0.8), "80%")
        XCTAssertEqual(u.label(1), "1 full")
        XCTAssertEqual(u.label(1.5), "1 full + 50%")
        XCTAssertEqual(u.leftLabel(0.8), "80% left")
        XCTAssertEqual(u.leftLabel(1), "Full")
        XCTAssertEqual(u.leftLabel(2), "2 full")
        XCTAssertEqual(Pantry.defaultStep(u), 0.25)
        XCTAssertEqual(Pantry.buyAmount(u, [bought(3)]), 1)
        let bread = Counting(unit: u, lowAt: 0.25)
        XCTAssertEqual(Pantry.status([bought(2), used(1, 0.25), used(0, 0.25)], bread, today), .ok)
        XCTAssertEqual(Pantry.status([bought(2), used(1, 0.5), used(0, 0.25)], bread, today), .low)
        XCTAssertEqual(Pantry.status([bought(9), used(1, 0.9)], Counting(unit: u, lowAt: 0.1), today), .low)
        XCTAssertEqual(Pantry.status([bought(9), used(1, 0.85)], Counting(unit: u, lowAt: 0.1), today), .ok)
    }

    func testGramsArePricedPerKg() {
        XCTAssertEqual(QtyUnit.GRAM.label(500), "500 g")
        XCTAssertEqual(Pantry.unitPriceMinor(bought(1, 500, 120), .GRAM), 240)
        XCTAssertEqual(Pantry.totalMinor(200, 500, .GRAM), 100)
        XCTAssertEqual(Pantry.buyAmount(.GRAM, []), 500)
        XCTAssertEqual(Pantry.unitPriceMinor(bought(1, 200, 300), .ML), 1500)
        XCTAssertEqual(Pantry.unitPriceMinor(bought(1, 0.5, 120), .KG), 240)
    }

    func testMealsLeftAndRed() {
        XCTAssertEqual(Meals.label(Meals.left(5, 1.6)), "about 8 meals")
        XCTAssertEqual(Meals.label(1), "1 meal")
        XCTAssertEqual(Meals.label(1.5), "1 to 2 meals")
        XCTAssertEqual(Meals.label(0.4), "less than 1 meal")
        XCTAssertEqual(Meals.perUnit(5, 8), 1.6)
        XCTAssertEqual(Meals.cookAmount(1, 4), 0.25)
        XCTAssertEqual(Meals.cookAmount(0.5, 0), 0.5)
        let potatoes = Counting(unit: .PIECE, lowAt: 2, mealsPerUnit: 0.6, lowInMeals: true)
        XCTAssertEqual(Pantry.status([bought(3, 5)], potatoes, today), .ok)
        XCTAssertEqual(Pantry.status([bought(3, 5), used(1, 2)], potatoes, today), .low)
        XCTAssertEqual(Pantry.status([bought(3, 5), used(1, 4)], potatoes, today), .last)
        let spaghetti = Counting(unit: .PACKET, lowAt: 0, mealsPerUnit: 4)
        XCTAssertEqual(Pantry.status([bought(3, 1), used(1, 0.5)], spaghetti, today), .ok)
        XCTAssertEqual(Pantry.status([bought(3, 1), used(1, 0.75)], spaghetti, today), .last)
    }

    func testCheaperElsewhereAndSpending() {
        let entries = [bought(30, 1, 199, "IKI"), bought(10, 1, 159, "Lidl"), bought(5, 1, 189, "Maxima"), bought(400, 1, 99, "Rimi")]
        XCTAssertEqual(Pantry.lastShop(entries), "Maxima")
        XCTAssertEqual(Pantry.cheaperElsewhere(entries, .PIECE, 199, shop: "IKI", today), Pantry.Cheaper(shop: "Lidl", unitPriceMinor: 159))
        XCTAssertNil(Pantry.cheaperElsewhere(entries, .PIECE, 150, shop: "IKI", today))
        let byItem: [Int64: [Entry]] = [1: [bought(2, 1, 100), bought(40, 1, 999)], 2: [bought(0, 1, 250), used(0)]]
        XCTAssertEqual(Pantry.spentBetween(byItem, today.plusDays(-10), today), 350)
    }

    func testSuggestionsCountMealsAndPieces() {
        XCTAssertEqual(Suggestions.forName("Red Onion")?.unit, .PIECE)
        XCTAssertEqual(Suggestions.forName("Potato")?.countMeals, true)
        XCTAssertEqual(Suggestions.forName("Pasta/spagheti")?.countMeals, true)
        XCTAssertEqual(Suggestions.forName("Salt")?.countMeals, false)
        XCTAssertEqual(Suggestions.forName("Oil")?.unit, .LEVEL)
        XCTAssertEqual(Suggestions.forName("Bread")?.unit, .LEVEL)
        XCTAssertEqual(Suggestions.forName("Tomato ketchup")?.unit, .LEVEL)
        XCTAssertEqual(Suggestions.forName("Chilli powder")?.category, "Spices")
        XCTAssertEqual(Suggestions.forName("Toothbrush")?.unit, .PIECE)
    }

    func testBackupKeepsMeals() throws {
        let item = Item(id: 3, name: "Potato", unit: QtyUnit.PIECE.rawValue, countMeals: true, mealsPerUnit: 0.6, lowInMeals: true)
        let back = try Backup.decode(Backup.encode([item], []))
        XCTAssertEqual(back.items.first?.countMeals, true)
        XCTAssertEqual(back.items.first?.mealsPerUnit, 0.6)
        XCTAssertEqual(back.items.first?.lowInMeals, true)
    }

    func testCravingsAreCountedAsWastedMoney() {
        let monday = Day(localDate: DateComponents(calendar: .current, year: 2026, month: 11, day: 2).date!)
        let entries: [Int64: [Entry]] = [
            1: [Entry(type: .BOUGHT, date: monday, quantity: 1, priceMinor: 300, shop: "IKI", craving: true),
                Entry(type: .BOUGHT, date: monday, quantity: 1, priceMinor: 200, shop: "IKI")],
            2: [Entry(type: .BOUGHT, date: monday.plusDays(10), quantity: 1, priceMinor: 900, shop: "IKI", craving: true)],
        ]
        let week = Pantry.week(monday, entries, categoryOf: { _ in "Other" })
        XCTAssertEqual(week.cravingMinor, 300)
        XCTAssertEqual(week.spentMinor, 500)
    }

    func testRecipesShowWhatIsMissing() {
        let stock: [Int64: Double] = [1: 1, 2: 0, 3: 0.5]
        let of: (Int64) -> Double = { stock[$0] ?? 0 }
        let chole = [RecipeLine(itemId: 1, needed: true, amount: 1), RecipeLine(itemId: 2, needed: false, amount: 0)]
        XCTAssertEqual(Recipes.status(chole, stockOf: of), .missingOptional)
        XCTAssertEqual(Recipes.missing(chole, stockOf: of, needed: false).map(\.itemId), [2])
        XCTAssertEqual(Recipes.status([RecipeLine(itemId: 1, needed: true, amount: 1), RecipeLine(itemId: 3, needed: true, amount: 0.5)], stockOf: of), .ready)
        XCTAssertEqual(Recipes.status([RecipeLine(itemId: 3, needed: true, amount: 1)], stockOf: of), .missing)
        XCTAssertEqual(Recipes.status([RecipeLine(itemId: 2, needed: true, amount: 0)], stockOf: of), .missing)
    }

    func testRecipeCostComesFromLastPrices() {
        let lines = [RecipeLine(itemId: 1, needed: true, amount: 2), RecipeLine(itemId: 2, needed: true, amount: 0)]
        XCTAssertEqual(Recipes.cost(lines, unitPriceMinor: { $0 == 1 ? 200 : nil }), 400)
        XCTAssertNil(Recipes.cost(lines, unitPriceMinor: { _ in nil }))
    }
}
