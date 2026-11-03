package com.diddyblud.casino;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/** Client-only custom screen. Game results are always calculated on the Paper server. */
public final class DiddybludCasinoClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("casino").executes(context -> {
                Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().gui.setScreen(new CasinoScreen()));
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("diddycasino").executes(context -> {
                Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().gui.setScreen(new CasinoScreen()));
                return 1;
            }));
        });
    }
}
