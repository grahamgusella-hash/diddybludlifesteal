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

## Dedicated-server-only update (October 2026)

The mod metadata now declares `environment: server`. The custom `lifesteal:heart` item registration was replaced with a **vanilla Nether Star marked with `minecraft:custom_data`** (`lifesteal_heart`) and custom name `Heart`; the server intercepts right-click to redeem exactly one maximum-health heart, up to 20. The `/withdraw`, `/customitems`, normal shop, and shard shop paths were updated to issue this tagged vanilla item. Ordinary untagged Nether Stars do not redeem. The vanilla Nether Star texture requires **no resource pack**. Menus use ordinary chest inventories.

**Existing worlds:** Old `lifesteal:heart` item stacks from earlier builds do not automatically migrate to the new tagged Nether Star; take precautions and replace/reissue them before removing the old mod. **This conversion is uncompiled and untested in Minecraft 26.3**; test on a throwaway server first. Other features (particularly spawner stacks, persistence, auctions and vault saving) remain prototype-only. The server still requires Fabric and Fabric API, but players should not need Fabric or the Lifesteal JAR client-side once server-only compatibility is verified.

## Functional vanilla mob spawners — latest source update

The server-only `LifestealSpawners` handler now creates vanilla **SPAWNER items with a custom_data mob marker**. The `/shop` Spawners section sells them for regular money (default $25,000 each, editable in $1,000 steps by OP shift-clicking entries), and `/customitems` gives OPs mob-specific spawners. Right-click the ground with a tagged spawner to place a real vanilla spawner, configured server-side with the mob type. Right-click an existing **matching** managed spawner with one in hand to increase its stack count (sneak for the whole held item stack); right-click empty-handed to open its manager GUI and remove one. Breaking a managed spawner by a player gives back its full count/type **without requiring Silk Touch**. Placed stack metadata is written to `lifesteal-spawners.properties` under the server world directory on each mutation and server stop, and read at startup.

**Limitations:** This is a first implementation, **not yet compiled or in-game tested against the exact 26.3 Fabric API**, so do not assume this will build as-is. These spawners use native Minecraft spawning rates: **stack count does not currently increase the spawn rate**. The management GUI's stored-drops/sell-all/XP buttons are still nonfunctional previews, because virtual production hasn't been implemented. This version only tracks blocks placed via the server's marked items; existing vanilla spawners are untouched. Only player-initiated breaks are tracked; explosions, pistons, world edits and other block removal paths can leave stale stack records. Before using on a live server, test on a new world and back up `lifesteal-spawners.properties` with the world. Older README sections describing preview-only spawners refer to earlier commits.

## Virtual stacked spawners (latest update, supersedes earlier spawner sections)

**Only nine spawners** are offered: Blaze ($600,000), Spider ($225,000), Iron Golem ($1,000,000), Enderman ($500,000), Chicken ($85,000), Zombie ($125,000), Creeper ($350,000), Pig ($60,000), Skeleton ($175,000). OPs can shift-click Spawner Shop entries to adjust prices ($1,000 per edit). All amounts are initial, editable balancing values.

Managed spawners are **virtual generators**: a server-side `BaseSpawner` mixin prevents those blocks from spawning mobs. While their chunk is loaded and the server runs, they produce a mob-specific resource and XP into server-side storage every **5 seconds**. The base output is the stacked spawner count, multiplied by `1 + min(50, count / 10)`: large stacks create more resources per interval. No generated items enter the world automatically. Generation stops when the chunk is unloaded or server is offline; storage caps at 1 billion resources and 100 million XP. The Spawner GUI (empty-hand right-click on a placed managed spawner) now supports collecting stored items, selling all stored items to balance, collecting XP, and extracting one spawner from a stack. Spawner count, produced resources and XP are saved in the world-level `lifesteal-spawners.properties`. Only the nine listed new types are valid.

**Not yet verified:** This is source code committed to GitHub, not a tested JAR. The new mixin and server tick methods must be tested against the exact 26.3 mappings; a build or runtime error is still possible. Player-specific ownership/protection, transactions with persistent currency, alternate reward items, and offline production are not implemented. Mobs are suppressed only for *managed placed spawners*, not existing vanilla world spawners. The basic economy is memory-only, so server restarts will reset cash balances even while spawner storage survives. Test in a disposable world and back up your world first.

## /sell chest GUI and permanent shop pricing

Regular players type **`/sell`** to open a 27-slot vanilla chest menu. Place the items you want to sell into the **top two rows (18 slots)**, then click the emerald in the bottom row to confirm. The server pays for eligible, enabled items whose selling prices are configured in the normal-money shop. Items without a positive sell price, or disabled items, are **not sold**, and any unsold items are returned to the player when the menu closes. Closing or clicking the barrier cancels and returns the deposits. The sell inventory blocks shift transfer and other advanced clicks to avoid moving items onto the confirmation controls.

OPs still use **`/sell price <buy> <sell>`** while holding an item to set **both** shop prices. For example `/sell price 500 250`. Both prices, plus changes to Shard Shop and Spawner Shop item prices in their admin menus, are now written to the world-specific **`lifesteal-shop-prices.properties`** file and reloaded on every server start. `/lifestealadmin setprice` also saves. Do not delete that file if you want the configuration to survive. This saves **prices**, not cash/shard balances or custom shop categories: those other aspects still have separate incomplete persistence.

**Compatibility:** The updated `/sell` GUI and persistence changes are source-only changes, not a compiled/tested 26.3 JAR. Test deposits, closing with unsold items, restart persistence, and overflow inventory handling in a disposable world before using them with valuable items.

## Configurable max hearts (latest)

The previous **20-heart limit** has been replaced by a **100-heart default maximum**. OP command `/hearts max <amount>` accepts 1–500 hearts, writes the configured limit to `lifesteal-heart-limit.properties` inside the world directory and survives server restarts. `/hearts max` displays the current limit. PvP transfer, Heart redemption, and admin heart limits use this setting; `/withdraw` accepts up to 64 hearts per command. This update is not yet compiled or in-game tested; a new server JAR must be built and deployed before its messages change.

## Short money amounts (latest)

The money and shard give commands and player payments accept case-insensitive suffixes:
- `k` = 1,000; `m` = 1,000,000; `b` = 1,000,000,000; `t` = 1,000,000,000,000.
- Examples: `/money give Steve 1b`, `/money give Steve 1.5m`, `/shards give Steve 50k`, `/pay Steve 250k`.
- Plain whole numbers remain valid. Zero, negative, fractional-unit and overflowed amounts are rejected. Command changes are committed as source and still need to be compiled and tested on Minecraft 26.3.

## Starter category stock (latest)

The normal-money `/shop` categories now contain approximately 70 individually priced starter entries, including combat supplies, enchanting materials, mob drops, all-purpose building materials, farm crops/food, utility and storage blocks, and redstone components. Items appear under explicit categories rather than relying solely on item-name classification. The **diamond icon still opens the separate Shard Shop**; the nine-type Spawner Shop is unchanged. Each item gets a default buy price, with default sell price at half unless an OP overrides it using `/sell price <buy> <sell>`. The new stock is added with `putIfAbsent`, so it does **not overwrite previously configured prices**; existing price-file settings load at server startup and take precedence. OPs can still edit individual prices and temporarily disable entries. As with the other changes, this is committed source code, not a verified successful Minecraft 26.3 build.
