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

## Halloween inventory GUI decorations

The Paper edition now uses **orange and black stained-glass pane borders, Jack o'Lantern corner icons and cobweb accents** to decorate unused GUI slots in `/shop`, category pages, the shard and spawner stores, `/sell`, `/ah`, `/orders`, the OP custom-items menu, `/rtp` and placed-spawner management. Existing clickable content and control slots take precedence; filler items are never placed over an active button. The sell menu's first **18 deposit slots remain empty**, and controls stay in their original positions.

Private vaults have a **Haunted Vault** title, but their 27 inventory slots are deliberately left undecorated so no storage is lost and no decorative items can be saved into player vault data. Decor items are not purchasable or withdrawable. This is a server-side design using ordinary vanilla items with no client resource pack or client mod required.

As with other commits, **compile and test the updated Paper JAR** before deploying to the live server.

## Verifying the actual deployed plugin (0.3.0-paper)

If GitHub commits don't appear in-game, the **running server may still be using an old JAR**. The new, distinctive build version is `0.3.0-paper`. After rebuilding or downloading the GitHub Actions artifact, the regular plugin file should be named `diddybludlifesteal-paper-0.3.0-paper.jar`.

1. Build **from the `paper/` Gradle project**, not the repository's older root Fabric build.
2. Stop FalixNodes Paper, and remove **all older Diddyblud Paper JARs** from the server's `plugins/` folder (keep backups outside `plugins/`).
3. Upload **only the new 0.3.0 Paper JAR**, restart, and run `/diddyblud`.
4. `/diddyblud` must print **Paper 0.3.0-paper**, together with `Heart right-click, Halloween GUIs, scoreboard, and shard rewards`. If it doesn't, the active server is **not running this update**.
5. As OP, run `/customitems`, click the new Heart (tagged Nether Star) to place it in your inventory, close the menu, and right-click it in **either main hand or offhand**. A successful redemption sends `Heart redeemed!`, adds one maximum heart (and heals that heart), and consumes one Heart in survival mode.
6. Ordinary Nether Stars and old Fabric Heart stacks without the Paper marker are **not** redeemable; get a fresh Paper Heart for the test.
7. Try `/shop` and look for Jack o'Lantern corners, orange/black glass, and cobweb accents. Decorations are protected against accidental menu purchases/clicks.

Neither these source changes nor the version bump prove the 26.3 project compiles and runs. **A successful 0.3.0-paper build and in-game version check are both required.**

## Named homes, locator bar, and broader item selling (0.4.0-paper)

- Players may store **up to 10 named homes** using `/sethome <name>`, travel using `/home <name>`, list them using `/homes`, and delete them using `/delhome <name>`. A home called `home` is the default if no argument is specified. Reusing a name replaces its saved location.
- Existing old-format single-home records are migrated in memory as the default home. Named home locations are saved to `server-data.yml` across restarts. Names support letters, numbers, underscore and hyphen, up to 24 characters.
- The Minecraft player **locator bar radar** is disabled in every world on plugin startup and on world load using the locator game rule. **The separate right-side money/shards scoreboard remains enabled.**
- The `/sell` deposit GUI uses a **conservative fallback payout** for ordinary vanilla obtainable items that have no configured sell price, so players can sell many more blocks, drops, materials, food and equipment than before. Explicit OP-configured prices always win. Rare resources pay more and easily farmed blocks pay less.
- Deliberate safeguards: admin/debug/unobtainable materials, unique virtual spawners, and nonempty shulker boxes cannot be sold using the fallback. Unrecognized enchanted book variants are also not currently sellable. This is *broad selling*, not an unconditional promise that every item on any modded server is accepted.
- After deployment, run `/diddyblud` to confirm that it reports **0.4.0-paper**. Build via the `paper/` Gradle project or GitHub Actions and install only the newest JAR in Paper `plugins/`.

Changes are committed as source but **not yet verified by compilation or server gameplay**. Test the updated JAR on a backup server before moving your live economy over.

## Update 0.5.0-paper: shop items, enchanted books, sidebar

- All ordinary vanilla items previously blocked by shop disable flags have been re-enabled for buying and selling. Regular categories are reset to enabled on load; normal shop items can no longer be switched off by the admin editor. Special custom entries can still be managed separately.
- All vanilla **enchanted books**, including books with several enchantments and those not listed in the shop, are sellable in `/sell`. Custom configured prices remain in use when positive; otherwise automatic pricing scales modestly with the stored enchantment levels.
- The right-side money/shard scoreboard is rebuilt on join and automatically restored if another plugin replaces it. Players can also use `/sidebar` (or `/scoreboard`) to re-create it manually. The radar locator bar remains disabled.
- Install **only** the updated **0.5.0-paper** JAR and confirm with `/diddyblud`. No code changes reach FalixNodes until the JAR is compiled and uploaded. If the sidebar still does not appear, inspect server startup errors and competing scoreboard plugins.

Source changes need compilation and server testing before being considered verified.

## 0.6.0-paper: Remove individual shop listings

An OP opens `/shop`, clicks **Admin settings**, chooses a category and **Shift + right-clicks** a product to **delete it from the shop**. The item is removed from that category and its configured shop buy/sell prices; existing items in players' inventories are unaffected. The deletion survives server restarts, including for original starter listings. The editor displays its controls in item lore.

To restore a deleted listing, hold the matching item and use **Add held item to category** in the category editor, then adjust prices as desired. Ordinary vanilla items can still be sold using the general `/sell` fallback pricing if removed from a *shop buy listing*; deleting a listing does not ban the material from the economy.

## 0.7.0-paper: Public hologram leaderboards

OPs can stand at their desired spawn location and place **three independent, public top-10 holograms** with:

- `/leaderboard money` — highest saved money balances
- `/leaderboard playtime` — longest accumulated online time
- `/leaderboard hearts` — highest maximum heart counts

Each command **places or moves** its leaderboard about 2.6 blocks above the OP's feet. All nearby players can see the floating text without a resource pack. Ranks refresh every **20 seconds**. The three locations are saved to `plugins/DiddybludLifesteal/leaderboards.yml` and rebuilt when the relevant world chunk is loaded after a restart. No world chunks are permanently forced to stay loaded.

Use `/leaderboard money remove`, `/leaderboard playtime remove`, or `/leaderboard hearts remove` to remove a placed hologram.

Player money is drawn from existing saved accounts. Hearts track maximum hearts as players join, redeem hearts, are assigned hearts, and die. **Playtime starts accumulating when this plugin version is installed**; earlier playtime is not backfilled. Offline players stay ranked based on the latest saved values. The plugin persists this data in `server-data.yml` on the normal periodic/quit/shutdown save intervals.

Requires Paper 26.3 and version **0.7.0-paper**. Code is committed, but a Gradle build and real in-game test are still required before deploying to a public spawn.

## 0.8.0-paper: Fixed /sell deposits returning to inventory

The first 18 slots in `/sell` accept deposits. Clicking the **emerald Confirm sale** immediately sells all eligible deposited stacks. **Closing the sell GUI normally also processes the eligible sale automatically**. Clicking the **barrier Cancel** instead returns every deposited item without selling anything. Invalid or zero-value items are returned with an explanatory message; valid items are consumed only after the cash deposit succeeds. For previously configured vanilla items with a zero sell price, the normal obtainable-item fallback price is now applied.

Make sure the running FalixNodes server actually has the newly built `0.8.0-paper` JAR in the Paper `plugins` directory and run `/diddyblud` to check version. Source changes are not a built/tested deployment.

## 0.8.3-paper: Lifesteal death rules corrected

- Every player death (PvP or environmental) **reduces maximum hearts by 1**.
- Every player death **drops one tagged redeemable Heart item**, regardless of the killer. Right-click the Heart to redeem it.
- A death which reduces the victim to **1 maximum heart** triggers the permanent vanilla `/ban` command and kicks the victim. Players at two hearts can survive until their next death; no automatic ban when holding one heart through administrative adjustments.
- Max hearts are persisted under `leaderboard-hearts` in `server-data.yml`, and re-applied on player join and after respawn to prevent health resetting.
- An OP may set a specific player's heart count with `/hearts set <player> <amount>`. Check `/version DiddybludLifesteal` shows `0.8.3-paper` after installation.

**Note:** These changes require a new Paper JAR build and have not been validated through in-game testing.
