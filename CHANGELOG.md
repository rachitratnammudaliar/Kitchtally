# Changelog

## 1.4.1

**Fixed**
- "Remind me at 0" now means only when it's gone. Before, an item you use fast (like bread) could still show on the shopping list with 1 left because it guessed you'd run out in 2 days
- Setting the stock yourself (Count / Set) now removes the item from the shopping list if it was marked as needing a refill

## 1.4.0

**New**
- **Shopping for next week:** switch it on (top of Pantry and To buy) before you log a shop, and everything you buy counts in next week's summary and spending. It stays in your pantry stock right away, and the switch turns itself off when the new week starts
- **Counts in: This week | Next week** on every purchase, so you can move one you already logged: open it and change it
- **Week starts on:** choose any day, in Settings

**Changed**
- Backups carry the new "counts in" setting; older backups still open

## 1.3.0

**New**
- **% left:** count bread, oil, ketchup and opened packets as "80% left" instead of "1 packet". Tap the amount to pick 100 / 75 / 50 / 25% / Empty or type any %
- **Grams and millilitres**, still priced per kg and per litre
- **Meals:** "Count meals" shows "4 pieces left · about 6 meals" and turns red when one meal is left. Set it on the item or when you buy ("these make about 8 meals"). Running low can be set in meals (e.g. 2 meals) or in % (e.g. 10% for oil)
- **🍳 Cook a meal:** tick what you cooked with; one meal comes off each in one tap
- **"Almost out" line** under items at their low point, no notifications
- **Shopping list by shop**, with ticks and **Done shopping** to log everything at once, and **Share** to send the list as text
- **Price check:** shows when another shop was cheaper last time
- **Home screen widget** (Android): To buy count and what's almost out
- **Optional monthly budget** under Weekly → Show spending

**Changed**
- Money is out of sight: the Pantry no longer shows what you spent, and Weekly opens with what's new this week. Spending is one tap away
- How long things last: Short is 2 to 3 days, Medium 1 week to 10 days, Long 2 weeks or more (no freshness reminder)
- New items fill in better: onions, potatoes, carrots and tomatoes in pieces; bread, oil, sauces, milk and spices in %; meals counted for vegetables, pasta, rice, eggs and meat
- Items counted in % or meals only say "almost out" at their own low point, never earlier

## 1.2.1

- **Fixed:** adding a photo on Android always said "That photo couldn't be used". Photos from the gallery, Downloads and the camera now work, including JPEG, PNG, WebP and HEIC

## 1.2.0

- **New categories:** Vegetables, Fruit, Staples, Sauces & spreads, Dairy & eggs, Meat & protein, Bread & bakery, Spices, Snacks, Drinks, Personal care, Household and Other. Old ones are renamed automatically (Grains & flour → Staples, Fruit & veg → Vegetables, Toiletries → Personal care, Cleaning → Household)
- **Fills itself in:** type "Eggs" and it picks Dairy & eggs, counted in pieces, packs of 10; "Garlic sauce" goes to Sauces & spreads, "Face cream" to Personal care, and so on. Change anything you like
- **Not sure to buy again:** one tap on an item (or Not now → Not sure to buy again on the shopping list) keeps it off the shopping list, even when it runs out. These items have their own group at the bottom of the pantry until you switch it off

## 1.1.0

**New**
- Packs: count eggs in pieces and set "packs of 10". ＋ adds a whole pack, − takes one egg, and purchases can be entered in packs
- Change any amount at once: tap an item's amount (or "Change amount…") for −2, −1, +1, +2 or a typed number, then choose Used, Shared, Bought, or set what's left
- Shopping list: type anything at the top, even things that aren't in your pantry, and tick them off. Typing a pantry item's name marks that item instead
- "Not now" on the shopping list hides an item until you next buy it
- Search your pantry
- "Each − uses" per item, for example 0.5 kg of rice per meal
- To buy count on the tab

**Fixed**
- − on kilos or litres took a whole kilo; it now uses the item's amount (0.5 by default) and never more than what's left
- ＋ on rice added 1 kg instead of your usual bag; for kilos and litres it now adds the size you last bought
- Tapping − quickly could take stock below zero
- The weekly "used" total added packets and kilos together; tiles now show items used, times shared and items bought
- The shopping list estimate used the price per kg; it now uses what you usually pay
- Undoing ＋ now puts the item back on the shopping list
- Long lists with many photos could run out of memory on Android; photos are now shown as small cached copies
- iPhone: saving an item with "amount now" empty reset its stock to zero
- Purchases can no longer be dated in the future
- "Price & shop" from the shopping list now returns to the shopping list
- A warning when an item with the same name already exists

## 1.0.0

First release.

- Items with photo, category, unit (pieces, packets, kg, litres…) and how long they last
- Tap ＋ when you buy more, − when you use some, and Shared when you cook for friends
- Stock count, "running low" and "out" warnings, and an estimate of how many days are left
- Purchases with date, total or price per kg, shop (IKI, Norfa, Maxima, Lidl, Rimi, Pepco or your own) and a receipt photo
- Shopping list from items that are out, running low or marked "refill needed"
- Weekly summary: what you used and shared, what you bought, and spending by category
- Backup to a file that works on Android and iPhone
- Fully offline, with no internet permission on Android
