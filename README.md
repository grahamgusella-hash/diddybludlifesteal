# Diddyblud Lifesteal SMP (Minecraft Java 26.3 / Fabric)

This GitHub repository contains an **unverified source prototype** of the Lifesteal mod.

## Included
- One Heart item, /hearts, /withdraw, basic PvP lifesteal
- OP money and shard grants, economy, homes, TPA, three numbered vaults
- Shop GUI with OP controls, /orders, /order create, /ah, /ah sell
- /rtp and /rtpq menus

## Important limitations
The source has NOT been successfully built or tested from GitHub. Compile or runtime errors may remain. Many data stores (money, shards, homes, vaults, shop prices, orders, auctions) are temporary and do not survive restarts. Spawner variants are display-only placeholders. No-Silk-Touch spawner drops and secure player trading are unfinished. Do not use on a live economy with valuable items yet.

## Using IntelliJ without ZIP downloads
To avoid conflicts with your newly created empty local project, open IntelliJ: File > New > Project from Version Control, select Git and clone this repository into a DIFFERENT empty folder. Select Java 25 for Gradle, sync and run Gradle > Tasks > build > build. Later source updates can be fetched with Git > Pull.

Deploy a verified regular JAR from build/libs into Fabric's mods folder (not Paper plugins), with matching Fabric API.

## New OP controls (October 2026)

- `/hearts give <player> <amount>` adds the specified number of maximum-health hearts, up to the 20-heart cap.
- `/hearts set <player> <amount>` sets the player's maximum hearts from 1 through 20.
- Both commands require Minecraft OP permissions; `/hearts` remains available to everyone.
- The Heart item now uses the vanilla Nether Star texture and a 26.3 item-model definition.
- The Custom Items Creative tab is populated only when the client reports operator-item permissions (`hasPermissions`). Its visibility must be checked in-game and may depend on the client's Operator Items setting. This is not an anti-cheat or security boundary.

These changes are pushed to GitHub but **have not been compiled or tested**. Run Gradle build in IntelliJ after pulling; check for 26.3 mapping errors before deployment.

## Server-side Custom Items menu

The separate Creative inventory tab was removed. The OP-only `/customitems` command opens a 27-slot chest-style GUI. OPs can click the Heart entry to obtain a Heart; named mob spawners are shown only as previews and cannot be granted yet because mob-type-specific spawner logic is unfinished. This is untested source code.

**Critical client-compatibility limitation:** The current Heart is still a registered `lifesteal:heart` modded item. A forced server resource pack does NOT register custom item IDs with unmodded Minecraft clients. To support vanilla 26.3 clients, it must be refactored into a vanilla base item (such as a Nether Star) tagged with custom item data and recognized on the server. Until that refactor is implemented and tested, users may still need the Fabric mod client-side. Do not expect a pack alone to fix missing textures or item networking.

FalixNodes supports custom pack ZIP upload under Minecraft > Server Properties > Resource Packs. Its upload interface creates the CDN URL and SHA-1 hash. A server may enforce it with `require-resource-pack=true` once the actual pack has been built and tested.

## Category-based /shop GUI

The shop now opens a 3-row category menu matching the supplied screenshot's item positions: Totems & Combat, Enchantments, Ores & Valuables, Mob Drops, Wood & Blocks, Farming & Food, Storage & Utility, and Redstone. Clicking a category opens its buy/sell item page. Existing item prices are preserved and assigned to categories automatically. OP players see Admin Settings in the shop; in edit mode, they can hold an item and add it to the current category, change prices (left/right click), or remove entries (shift-click). These configurations currently reset on restart and the implementation is untested. Only the source on GitHub is updated: pull and compile before deployment.

## Shop categories, shards and spawners (latest)

- The diamond slot opens a **Shard Shop** using the existing shard balance, not regular money. OPs can edit its item list and shard prices inside the GUI. Default example listings: Diamond (50 shards), Netherite Ingot (250 shards), and Heart (500 shards); values can be changed in the editor.
- In `/shop`, an OP clicks Admin Settings, renames a held item in an anvil, then clicks the Anvil **Add Category** button (slot 19). The held item's custom name becomes the category name and the held item becomes its icon. New categories can be browsed using the page arrows; click a category while editing to add items and prices.
- A new Spawners category displays a paginated catalog of 37 mob types. **Purchases are intentionally disabled**: the mod still lacks reliable mob-specific block-entity data, placement for non-OP players, and mining/dropping with correct mob identity. The labels are previews only, not functional custom spawners.
- These GUI settings currently exist only in memory and reset when the server restarts. Neither successful compilation nor in-game compatibility has been verified. Do not deploy to a production economy until persistence, transaction safety, and the full spawner mechanics are implemented and tested.

## Temporary shop controls and direct OP price command

- `/shop` -> Admin Settings: **shift-click a category icon** to disable/enable it. Regular players cannot open disabled categories; OPs can open them while editing.
- In a category's Admin Settings, **shift-click a listing** to temporarily disable/enable that item instead of removing it. This also applies to Shard Shop listings. Disabled items cannot be purchased or sold through the normal-money shop (and cannot be purchased in the Shard Shop).
- OPs can hold an item in the main hand and run `/sell price <buyPrice> <sellPrice>` to set its regular-money shop purchase and sale price. Example: `/sell price 500 250`. This adds the held item's type to the shop if absent; it does not change shard prices. Both prices can be 0 or higher. Existing listings use the configured sell value instead of automatically halving the buy price.
- **All these settings are temporary:** toggles and custom sell prices reset when the server restarts. This implementation has not yet been compiled/tested in Minecraft 26.3. Shop and auction persistence and economy safeguards are still pending.

## Spawner stacking and Donut-style UI (work in progress)

`/spawner` now opens a **non-transactional preview** of a 27-slot spawner manager: chest/storage, player-head/sell all, experience bottle/collect XP, anvil/stack information. Click the anvil for intended stack controls (one spawner by right-click; full held stack by Shift-right-click; no Silk Touch on break). These controls are **not functional yet** and intentionally do not alter player inventory or balances.

**Remaining before enabling real spawner stacking:** each mob-specific spawner item must preserve its mob type when placed; tracked placed spawners must store stack count, mob type, drops and XP across restarts; clicking and breaking must be transaction-safe and enforce ownership/permissions; virtual production must be configured and tested; mining without Silk Touch must safely return the correct quantity and type. The current mod does not yet meet these requirements. Do not treat the GUI preview as a finished feature.
