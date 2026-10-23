# Diddyblud Lifesteal — Paper 26.3 Edition

This is the **Paper plugin** rewrite of the old Fabric project. The Paper plugin lives entirely in the `paper/` subfolder; the original Fabric source remains in the repository for reference.

## Included in the Paper source

- **Lifesteal:** steal one maximum heart on PvP kill; redeem tagged vanilla Nether Star Hearts with right-click; `/withdraw`, `/hearts`, OP `/hearts set`, `/hearts give` and persistent `/hearts max` (default 100, configurable up to 500).
- **Persistent economy:** `/bal`, `/balance`, `/money give` (OP), `/pay`, `/shards` and OP `/shards give`. All amounts support `k/m/b/t` shortcuts and decimal abbreviations such as `1.5m`.
- **Shop:** nine category icons, page navigation, buy with left-click, sell one item with right-click. Combat includes armor, mace, crystals and obsidian. Enchantments generate actual enchanted books; the catalog includes standard enchantments at their maximum levels.
- **Admin shop:** comparator toggles editor, shift-click category to temporarily disable/re-enable, hold a renamed item and press the anvil to add a new category; in category editor shift-click item to toggle, left-click to raise buy price and right-click to raise sell price. All these settings save to the plugin YAML.
- **Sell chest:** `/sell` opens a deposit GUI with 18 item slots and confirm/cancel. Eligible item payouts use saved prices, remaining items are returned on closing. OP `/sell price <buy> <sell>` changes both prices of the held item, saved across restarts.
- **Virtual spawners:** exactly Blaze, Spider, Iron Golem, Enderman, Chicken, Zombie, Creeper, Pig, Skeleton. They are vanilla spawner items with a persistent data marker and place a normal-looking spawner block, but **no mobs spawn** from tracked stacks. Loaded chunks produce stored drops and XP every five seconds; stacked generators multiply yield. Empty-hand right-click opens a GUI to collect items, sell all, collect XP, or unstack one; mining the spawner recovers its type and count without Silk Touch. Prices depend on rarity and can be adjusted with admin shift-click in Spawner Shop. Player ownership and explosion protections included. Data saved to `spawners.yml`.
- **Other commands:** `/sethome`, `/home`, `/delhome`, `/tpa`, `/tpaccept`, `/tpdeny`, `/rtp` near/far GUI, two-player `/rtpq` (and leave), `/pv` and `/vault` (three saved vaults), `/ah` and `/ah sell <price>`, `/orders`, `/order create <item> <count> <eachPrice>`, OP `/customitems`.

All user-facing menus use ordinary vanilla Minecraft chest inventories. **Players should not install a Fabric mod.** The server runs Paper and the Paper plugin only.

## Installing / building

### Option A: GitHub Actions (recommended)

1. Open the repository's **Actions** tab and choose **Build Paper plugin**.
2. Select **Run workflow**. If it succeeds, open that run and download its **DiddybludLifesteal-Paper-26.3** artifact.
3. Extract the downloaded artifact ZIP to find the JAR inside.
4. In FalixNodes, **stop the server** and back up the current world before changing software.
5. Select **Paper 26.3** in the server version/installer panel.
6. Remove the old Fabric mod JAR from the active installation. Place the ordinary `diddybludlifesteal-paper-0.2.0-paper.jar` in the **`plugins/`** directory.
7. Restart and check the console for `Diddyblud Paper enabled`.
8. Join with ordinary Minecraft Java 26.3, run `/diddyblud`, `/shop`, and `/bal`, then test spawners on a temporary world.

### Option B: IntelliJ

Open **only `paper/` as a separate Gradle project**, configure JDK 25 and build. The root Gradle project still builds **Fabric**, not Paper. The resulting ordinary JAR is in `paper/build/libs/`.

## Important limitations and testing

**Source code has been written and committed, but the Paper port has not been compiled or runtime-tested in this environment.** A successful GitHub Actions build is required before uploading a JAR; runtime testing is still necessary. Do **not** assume the JAR exists or that all features work until checks pass.

- This port uses new YAML files under `plugins/DiddybludLifesteal/`, including `server-data.yml` and `spawners.yml`. The prior Fabric mod's world data **does not migrate automatically**. Preserve all original world and configuration backups.
- The plugin's spawner collection uses server ticks only in loaded chunks; no offline generation. NPCs and vanilla-world spawners are not automatically converted. Do not place high-value items or unlock a production server without testing inventory workflows and server restarts.
- Economy balances are stored in a YAML file: very busy public servers should move to a database-backed economy and transaction-safe inventory/escrow layer.
- Shop prices are starter balancing values; admins can reprice to match the server economy.
- Server software switching must be done through FalixNodes; updating code on GitHub cannot change a running host or install a plugin remotely.

## Global server spawn and shutdown saves (latest update)

- OP only: `/setspawn` stores the OP's exact current location (world, coordinates, yaw and pitch) as the **single shared server spawn**. It also updates that world's vanilla spawn coordinates as a fallback.
- Everyone: `/spawn` teleports to the shared location. If nobody has used `/setspawn` yet, the command explains that spawn is not configured.
- All player deaths respawn at the configured server spawn using the Paper respawn event, rather than individual beds/anchors (provided the world for the saved spawn exists).
- Global spawn, all three private vault contents, shop categories and assignments, item buy/sell prices, shard prices, balances, homes and related game data are stored in `plugins/DiddybludLifesteal/server-data.yml`. If a vault is open during server shutdown, its contents are snapshotted directly before saving.
- Mob-specific virtual spawner stacks, stored drops/XP and spawner shop prices are saved separately to `plugins/DiddybludLifesteal/spawners.yml`.
- Both YAML files are written to temporary files and moved into place on successful save, reducing the chance of a truncated data file. Always stop the server normally and make world/plugin backups before software changes.
- **These are source changes, not a verified compiled or tested Paper 26.3 plugin.** GitHub Actions must succeed before using the JAR, followed by in-game checks of vault contents, global spawn after restart, respawn behaviour and saved shop/spawner settings.

## OP balance and heart setting commands

All three commands require OP/admin permission and an **online player**:

- `/hearts set Steve 20` — set Steve to exactly 20 maximum hearts (bounded by `/hearts max`).
- `/money set Steve 1m` — replace Steve's money balance with exactly $1,000,000.
- `/shards set Steve 50k` — replace Steve's shard balance with exactly 50,000.

Both money and shards accept whole numbers or `k`, `m`, `b`, `t` shorthand; setting either to `0` is allowed. These are **set**, not add, operations. The existing `/hearts give`, `/money give`, and `/shards give` still add amounts. Money and shard updates save immediately to `server-data.yml`; maximum-health updates use the player attribute persisted by Minecraft's player data. Source remains uncompiled and untested; run the Paper GitHub Actions workflow before deployment.

## Lifesteal elimination and PvP heart drops

- **Every death costs one maximum heart**, including environmental deaths and PvP.
- On a player's **final heart**, dying triggers the vanilla server `/ban` command on the next server tick; the ban persists across restarts using Paper's normal ban list. The player's maximum health is left at the last valid value because Minecraft does not allow a zero maximum-health attribute.
- When a player kills another player, the victim's death loot receives **one genuine, tagged Lifesteal Heart** (Nether Star), which can be picked up and redeemed by right-clicking. The killer no longer gains the heart automatically. Regular environmental deaths do not create a heart drop.
- **Unbanning** an eliminated player remains a server operator decision (vanilla `/pardon <player>`). Pardoning alone does not reset the player's stored heart count.
- These changes are committed to the Paper source, **not yet compiled or tested**. Before using a production world, verify both the ban flow and death-drop persistence on a test player account.

## Right-hand SMP scoreboard & timed shard rewards (latest)

Every online player gets a **vanilla Minecraft right-hand sidebar scoreboard** titled `DIDDYBLUD SMP` with:

- **Username** — each player sees their own Minecraft username above the balances.
- **Money** — current balance, displayed compactly (e.g., `$1.5m`).
- **Shards** — current shard balance.
- **Next** — countdown `MM:SS` to the next reward.
- **Reward** — `+10 Shards`.

Players receive **10 shards for every 30 minutes actively connected to the server**. The countdown pauses while offline; reconnecting resumes the saved remaining time rather than resetting it. Rewards deposit directly into the existing shard balance and are immediately saved. Remaining time is stored in `server-data.yml` under `shard-reward-seconds`, alongside balances, shop, vault and spawn configuration. The timer also saves during periodic saves, quit, and normal shutdown.

The scoreboard is sent using the built-in Bukkit scoreboard API: **no client mod required**. This plugin uses the player's sidebar scoreboard slot, so an additional scoreboard plugin could conflict and overwrite its display.

As with the other Paper code, these changes are committed as source; **building successfully and testing in-game are still required** before a deployment is considered verified.
