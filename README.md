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
