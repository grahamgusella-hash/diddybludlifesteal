# Diddyblud Lifesteal Paper 26.3 — EARLY MIGRATION

This is a **separate Paper plugin project**, alongside (not replacing) the root Fabric project. Do not upload a Fabric mod into Paper `plugins/`.

## Status — deliberately limited

This first Paper port contains:
- Paper 26.3 Gradle project and `plugin.yml`, Java 25
- `/diddyblud` migration/status command
- **persistent** money balances (`plugins/DiddybludLifesteal/balances.yml`)
- `/bal`, OP-only `/money give <player> <amount>`, `/pay <player> <amount>`; support for k/m/b/t suffixes
- `/shop` opens a nine-category **preview** using a vanilla 27-slot chest menu

**NOT YET PORTED:** full item shop and admin pricing, `/sell` deposit GUI, Lifesteal hearts and withdrawals, shards, auctions, buy orders, spawner stacking and virtual drop generation, homes and teleports, vaults, admin category editing, and historical Fabric saves. Do not run this migration build expecting those features. The Paper implementation is NOT a drop-in equivalent of the Fabric mod; it also does not migrate prior world data automatically.

## Build

1. Open **the `paper/` subfolder itself as a Gradle project** in IntelliJ. The root repository Gradle build still builds Fabric.
2. Use Java 25 and run `build`.
3. If the build succeeds, use **`paper/build/libs/diddybludlifesteal-paper-0.1.0-paper.jar`** (or the ordinary non-sources JAR).
4. Install on a **Paper 26.3** test server, in `plugins/`, then restart. Do not install the old Fabric JAR; remove any conflicting old plugin version.
5. Test `/diddyblud`, `/bal`, `/money give <player> 1k`, `/pay <player> 100`, and `/shop`.

This project is source-only and has **not** been compiled or tested here. Make a world backup first. Paper and Fabric server versions need careful world handling; do not delete data without a backup.
