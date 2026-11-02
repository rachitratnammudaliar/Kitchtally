# Changelog

## 1.1.0

**New**
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
