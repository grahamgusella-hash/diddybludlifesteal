# Lifesteal SMP — Java Edition 26.3 (prototype v0.2)

**Not a compiled, installable mod. Not tested or safe for a live multiplayer server.**

## Newly added in v0.2
- `/pv`, `/pv 1`, `/pv 2`, `/pv 3` (or `/vault ...`) open 27-slot *private* inventories. Inventories are keyed by player UUID and vault number and cannot be opened by another player using these commands. **IMPORTANT: currently stored in RAM only, so inventory contents are LOST on every server restart or crash. Do not store valuables!** Vault duplication/exploit testing has not been done.
- The Creative **Custom Items** tab includes the existing single Heart plus named prototypes for Zombie, Skeleton, Spider, Creeper, Enderman, Blaze, Pig, Cow, Sheep, Chicken, and Iron Golem Spawners. These are **name-only placeholders**: all are still plain vanilla spawner items, not working mob-specific spawners.
- **Mining spawners without Silk Touch is NOT YET IMPLEMENTED.** Correct implementation must capture the spawner block entity spawn configuration before block destruction, create a mob-typed item, preserve its configuration on placement, and prevent double drops. This is intentionally not claimed as working.
- Updated Gradle configuration toward the official 26.3 Fabric build conventions (Java 25, Fabric Loom unremapped plugin, API 0.161.0+26.3). Build verification was not possible in this environment.

## Previously included prototype source
- Single `lifesteal:heart` item (right-click to redeem), one Custom Items tab.
- Kill-to-steal-heart, `/withdraw`, `/hearts`, `/balance`, `/bal`, `/pay`.
- `/sethome`, `/home`, `/delhome`, `/tpa`, `/tpaccept`, `/tpdeny`.
- `/shop` chat preview and `/lifestealadmin setprice` session-only command.

## Still unfinished
- Working mob-specific spawners, no-Silk-Touch mining, server-persistent vault inventories and hearts/economy/homes.
- Admin editing inside `/shop` GUI, trading, `/rtp`, and the full Donut SMP commands.
- Compile/runtime verification and exploit testing.

## Building (developer)
1. Install JDK 25, Gradle 9.6, and compatible Fabric dependencies for 26.3.
2. Open this directory and run `gradle build`.
3. Correct remaining 26.3 source mapping/API mismatches reported by the compiler.
4. **Never deploy this prototype with real players' possessions** until vaults and all important values persist reliably and tests pass.

## Admin currency commands (added in v0.3)

- `/money give <player> <amount>` — add money to an online player's balance (operator permission level 2).
- `/shards give <player> <amount>` — add shards to an online player's balance (operator permission level 2).
- `/shards` — check your shard balance (available to all players).
- `/balance` / `/bal` — check your money balance (existing commands).

These grant commands accept positive whole numbers and reject grants that overflow a 64-bit balance. Console operators can grant to online players. **Important:** both currencies are still kept only in memory; balances reset after restart. This is an uncompiled source prototype, not a production-ready mod.


## NEW: GUI commands (uncompiled prototype)
- `/rtp`: two clickable current-dimension random teleport choices (near or far). Searches for an above-ground air destination; this is **not** a guarantee of safe locations in every dimension/terrain.
- `/rtpq`: join a two-player queue via GUI; `/rtpq leave` leaves the queue. Queued players must be in the same dimension; queue is held in memory.
- `/shop`: server-side chest GUI to purchase (left-click) or sell one (right-click). OPs see **Admin Settings** inside the same GUI; editor: left-click price +$100, right-click -$100, shift-click remove; hold a new item and click the anvil button to add at $100.
- `/ah`: auction GUI. Hold a stack and use `/ah sell <price>` to create a listing; click listings to purchase; click your own listing to cancel.
- `/orders`: GUI for player buy orders. `/order create minecraft:diamond 10 100` escrows $1000; click to fill if enough items, or click your own listing to cancel/refund. Owner must be online with inventory space to fulfill.

**Limitations / security notice:** These additions are source code only and have not been compiled against 26.3 here. Shop, auctions, orders, balances, and queues are **memory-only and reset on restart**, and unsold auction escrow items and unfilled order escrow funds can be lost on restart. Do not run with real players' valuable items or money. GUI controls aren't yet fully production-tested. The spawner features and persistent vaults remain unfinished. `/rtp` location safety and chunk loading require further testing. For the current build, compile and review in IntelliJ before any server installation; no functioning JAR is included.


## Compilation correction (2026-10-22)
Replaced the removed `ClickType` API with `ContainerInput` in SmpMenus.java and corrected the shop price map declaration. This source has not been locally compiled (Java 25 build environment unavailable).
