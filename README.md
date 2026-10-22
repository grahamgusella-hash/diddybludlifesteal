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
