# Diddyblud Casino Client (Fabric 26.3)

This original companion mod displays a real non-inventory casino screen. The Paper server remains unmodded except for DiddybludLifesteal v0.8.37 or newer.

## Install
Install Fabric Loader for Minecraft 26.3 and Fabric API. Copy the built client mod JAR into the player's mods folder. Do not put this client JAR on Paper.

Use **/casino** or **/diddycasino** to open the custom Games / Auctions interface.
The client sends authoritative commands to the Paper plugin: `/gamble play <game> <stake>`, `/gamble auction <start|stop|log>`.
No balance is changed on the client, and all wager outcomes are generated on the server.

The unmodified **/gamble** continues to open the legacy chest-style interface for players without the client mod.

This is an original interface, not a repackaging or copy of GamblerPlus.
