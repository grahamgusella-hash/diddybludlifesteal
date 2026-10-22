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
