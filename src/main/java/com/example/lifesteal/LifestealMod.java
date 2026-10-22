package com.example.lifesteal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * WORK-IN-PROGRESS SOURCE: 26.3 mapping signatures must be validated in a real Gradle build.
 * Server persistence, inventory menus, secure trade transactions and remaining commands
 * are NOT implemented by this prototype. See README before installing.
 */
public class LifestealMod implements ModInitializer {
    private static final String MODID = "lifesteal";
    public static Item HEART;
    // The limit is counted in hearts, not health points. OP-configurable.
    private static int maxHearts = 100;
    private static net.minecraft.server.MinecraftServer heartConfigServer;
    private static java.nio.file.Path heartConfigPath() {
        return heartConfigServer.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("lifesteal-heart-limit.properties");
    }
    private static void readHeartLimit(net.minecraft.server.MinecraftServer server) {
        heartConfigServer = server;
        java.nio.file.Path path = heartConfigPath();
        if (!java.nio.file.Files.exists(path)) return;
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
            props.load(in);
            int value = Integer.parseInt(props.getProperty("max-hearts", "100"));
            if (value >= 1 && value <= 500) maxHearts = value;
        } catch (Exception ex) {
            System.err.println("[Lifesteal] Could not load heart limit: " + ex.getMessage());
        }
    }
    private static void saveHeartLimit() {
        if (heartConfigServer == null) return;
        try {
            java.nio.file.Path path = heartConfigPath();
            java.nio.file.Files.createDirectories(path.getParent());
            java.util.Properties props = new java.util.Properties();
            props.setProperty("max-hearts", Integer.toString(maxHearts));
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(path)) {
                props.store(out, "Diddyblud Lifesteal heart cap");
            }
        } catch (Exception ex) {
            System.err.println("[Lifesteal] Could not save heart limit: " + ex.getMessage());
        }
    }
    private static final Map<UUID, Long> BALANCE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SHARDS = new ConcurrentHashMap<>();
    private static final Map<UUID, net.minecraft.core.BlockPos> HOMES = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> TP_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> SHOP = new LinkedHashMap<>();
    private static final Map<UUID, Map<Integer, SimpleContainer>> VAULTS = new ConcurrentHashMap<>();
    private static final int VAULT_COUNT = 3;
    private static final int VAULT_SIZE = 27;

    private static int openVault(ServerPlayer player, int number) {
        if (number < 1 || number > VAULT_COUNT) return tell(player, "Vault number must be 1 to " + VAULT_COUNT + ".");
        SimpleContainer inventory = VAULTS.computeIfAbsent(player.getUUID(), id -> new ConcurrentHashMap<>())
                .computeIfAbsent(number, id -> new SimpleContainer(VAULT_SIZE));
        player.openMenu(new SimpleMenuProvider((containerId, playerInventory, user) ->
                ChestMenu.threeRows(containerId, playerInventory, inventory),
                Component.literal("Private Vault #" + number)));
        return 1;
    }

    static Map<UUID, Long> economy() { return BALANCE; }
    static Map<UUID, Long> shardBalances() { return SHARDS; }
    static Map<String, Long> shopPrices() { return SHOP; }

    @Override public void onInitialize() {
        SmpMenus.init();
        LifestealSpawners.init();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
            .register(LifestealMod::readHeartLimit);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
            .register(server -> saveHeartLimit());
        // Vanilla Nether Star, identified via persistent vanilla custom_data.
        // Do NOT register a new item ID: a vanilla client cannot decode that registry.
        HEART = Items.NETHER_STAR;
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!isHeart(held)) return InteractionResult.PASS;
            return redeemHeart(sp, held);
        });
        // The OP-only /customitems chest GUI replaces the Creative inventory tab.
        SHOP.put("minecraft:diamond", 500L);
        SHOP.put("minecraft:netherite_ingot", 5000L);
        SHOP.put("minecraft:golden_apple", 800L);
        SHOP.put("lifesteal:heart", 10000L); // virtual shop key; granted as tagged Nether Star
        SmpMenus.seedDefaultShopStock();

        ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
            if (!(victim instanceof ServerPlayer loser) || !(source.getEntity() instanceof ServerPlayer winner)) return;
            if (loser.getUUID().equals(winner.getUUID())) return;
            var l = loser.getAttribute(Attributes.MAX_HEALTH);
            var w = winner.getAttribute(Attributes.MAX_HEALTH);
            if (l == null || w == null || l.getBaseValue() <= 2 || w.getBaseValue() >= maxHearts * 2.0) return;
            l.setBaseValue(l.getBaseValue() - 2);
            w.setBaseValue(w.getBaseValue() + 2);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(literal("withdraw").then(argument("amount", IntegerArgumentType.integer(1, 64))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                    var health = p.getAttribute(Attributes.MAX_HEALTH);
                    if (health == null || health.getBaseValue() - amount * 2 < 2) return tell(p, "You must keep at least one heart.");
                    ItemStack stack = heartStack(amount);
                    if (p.getInventory().getFreeSlot() < 0) return tell(p, "Make room in your inventory first.");
                    health.setBaseValue(health.getBaseValue() - amount * 2);
                    p.getInventory().add(stack);
                    return tell(p, "Withdrew " + amount + " heart(s).");
                })));
            dispatcher.register(literal("hearts").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                return tell(p, "Hearts: " + (int)(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue()/2));
            })
                .then(literal("max").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(ctx -> tell(ctx.getSource().getPlayerOrException(),
                        "Current maximum: " + maxHearts + " hearts."))
                    .then(argument("amount", IntegerArgumentType.integer(1, 500))
                        .executes(ctx -> {
                            maxHearts = IntegerArgumentType.getInteger(ctx, "amount");
                            saveHeartLimit();
                            return tell(ctx.getSource().getPlayerOrException(),
                                "Server heart maximum is now " + maxHearts + " (saved).");
                        })))
                // OP-only; a granted heart means one extra maximum-health heart, not an item.
                .then(literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(argument("player", EntityArgument.player())
                    .then(argument("amount", IntegerArgumentType.integer(1, 64))
                    .executes(ctx -> changeHearts(
                        ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                        IntegerArgumentType.getInteger(ctx, "amount"), false)))))
                .then(literal("set").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(argument("player", EntityArgument.player())
                    .then(argument("amount", IntegerArgumentType.integer(1, 500))
                    .executes(ctx -> changeHearts(
                        ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                        IntegerArgumentType.getInteger(ctx, "amount"), true))))));
            dispatcher.register(literal("balance").executes(ctx -> balance(ctx.getSource().getPlayerOrException())));
            dispatcher.register(literal("bal").executes(ctx -> balance(ctx.getSource().getPlayerOrException())));
            dispatcher.register(literal("shards").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                return tell(p, "Shards: " + SHARDS.getOrDefault(p.getUUID(), 0L));
            }).then(literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(argument("player", EntityArgument.player())
                .then(argument("amount", StringArgumentType.word())
                .executes(ctx -> giveCurrencyShort(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "player"),
                    StringArgumentType.getString(ctx, "amount"), SHARDS, "shards"))))));
            dispatcher.register(literal("money")
                .then(literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(argument("player", EntityArgument.player())
                .then(argument("amount", StringArgumentType.word())
                .executes(ctx -> giveCurrencyShort(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "player"),
                    StringArgumentType.getString(ctx, "amount"), BALANCE, "money"))))));
            dispatcher.register(literal("pay").then(argument("player", EntityArgument.player())
                .then(argument("amount", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    long amt;
                    try { amt = parseShortAmount(StringArgumentType.getString(ctx, "amount")); }
                    catch (IllegalArgumentException ex) { return tell(p, ex.getMessage()); }
                    if (p.getUUID().equals(target.getUUID())) return tell(p, "You cannot pay yourself.");
                    long funds = BALANCE.getOrDefault(p.getUUID(), 0L);
                    if (funds < amt) return tell(p, "Not enough money.");
                    long targetFunds = BALANCE.getOrDefault(target.getUUID(), 0L);
                    if (amt > Long.MAX_VALUE - targetFunds) return tell(p, "Recipient balance would overflow.");
                    BALANCE.put(p.getUUID(), funds - amt);
                    BALANCE.put(target.getUUID(), targetFunds + amt);
                    tell(target, "You received $" + amt + " from " + p.getName().getString());
                    return tell(p, "Paid $" + amt + ".");
                }))));
            dispatcher.register(literal("sethome").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                HOMES.put(p.getUUID(), p.blockPosition());
                return tell(p, "Home set (current dimension only, session-only).");
            }));
            dispatcher.register(literal("delhome").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                HOMES.remove(p.getUUID()); return tell(p, "Home deleted.");
            }));
            dispatcher.register(literal("home").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                var pos = HOMES.get(p.getUUID());
                if (pos == null) return tell(p, "No home set.");
                p.teleportTo(pos.getX()+0.5, pos.getY(), pos.getZ()+0.5);
                return tell(p, "Teleported home.");
            }));
            dispatcher.register(literal("tpa").then(argument("player", EntityArgument.player()).executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                ServerPlayer target = EntityArgument.getPlayer(ctx,"player");
                if (p == target) return tell(p, "Cannot teleport to yourself.");
                TP_REQUESTS.put(target.getUUID(), p.getUUID());
                tell(target, p.getName().getString()+" requested teleport. Use /tpaccept or /tpdeny.");
                return tell(p, "Teleport request sent.");
            })));
            dispatcher.register(literal("tpdeny").executes(ctx -> {
                ServerPlayer p=ctx.getSource().getPlayerOrException(); TP_REQUESTS.remove(p.getUUID()); return tell(p,"Request denied.");
            }));
            dispatcher.register(literal("tpaccept").executes(ctx -> {
                ServerPlayer p=ctx.getSource().getPlayerOrException(); UUID id=TP_REQUESTS.remove(p.getUUID());
                if(id==null) return tell(p,"No pending request.");
                ServerPlayer from=ctx.getSource().getServer().getPlayerList().getPlayer(id);
                if(from==null) return tell(p,"Player offline.");
                from.teleportTo(p.getX(),p.getY(),p.getZ()); return tell(p,"Teleport accepted.");
            }));
            dispatcher.register(literal("pv")
                    .executes(ctx -> openVault(ctx.getSource().getPlayerOrException(), 1))
                    .then(argument("number", IntegerArgumentType.integer(1, VAULT_COUNT))
                            .executes(ctx -> openVault(ctx.getSource().getPlayerOrException(),
                                    IntegerArgumentType.getInteger(ctx, "number")))));
            dispatcher.register(literal("vault")
                    .executes(ctx -> openVault(ctx.getSource().getPlayerOrException(), 1))
                    .then(argument("number", IntegerArgumentType.integer(1, VAULT_COUNT))
                            .executes(ctx -> openVault(ctx.getSource().getPlayerOrException(),
                                    IntegerArgumentType.getInteger(ctx, "number")))));
            dispatcher.register(literal("lifestealadmin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(literal("setprice").then(argument("item",StringArgumentType.word())
                .then(argument("price",IntegerArgumentType.integer(0)).executes(ctx -> {
                    String id=StringArgumentType.getString(ctx,"item");
                    long price=IntegerArgumentType.getInteger(ctx,"price"); SHOP.put(id,price);
                    SmpMenus.saveShopSettings();
                    return tell(ctx.getSource().getPlayerOrException(),"Saved buy price for "+id+" as $"+price);
                })))));
        });
    }
    /** Change player maximum health in heart units (2 health points per heart). */
    private static int changeHearts(net.minecraft.commands.CommandSourceStack source,
                                    ServerPlayer player, int hearts, boolean set) {
        var maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            source.sendSystemMessage(Component.literal("Unable to change player hearts."));
            return 0;
        }
        int current = (int) Math.round(maxHealth.getBaseValue() / 2.0);
        int resulting = set ? hearts : current + hearts;
        if (resulting < 1 || resulting > maxHearts) {
            source.sendSystemMessage(Component.literal("Heart limit is 1 to " + maxHearts + ". "
                    + player.getName().getString() + " currently has " + current + "."));
            return 0;
        }
        maxHealth.setBaseValue(resulting * 2.0);
        // When lowering the maximum, avoid leaving health above the new limit.
        if (player.getHealth() > maxHealth.getBaseValue()) {
            player.setHealth((float) maxHealth.getBaseValue());
        }
        player.sendSystemMessage(Component.literal("Your maximum hearts were "
                + (set ? "set to " : "increased to ") + resulting + " by an OP."));
        source.sendSystemMessage(Component.literal("Set " + player.getName().getString()
                + "'s maximum hearts to " + resulting + "."));
        return 1;
    }

    /** Vanilla-Nether-Star-based Heart. No custom item registry entry is required. */
    public static ItemStack heartStack(int amount) {
        ItemStack stack = new ItemStack(Items.NETHER_STAR, amount);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Heart"));
        net.minecraft.world.item.component.CustomData.update(
            net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack,
            tag -> tag.putBoolean("lifesteal_heart", true));
        return stack;
    }

    public static boolean isHeart(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.NETHER_STAR)
            && stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().contains("lifesteal_heart");
    }

    private static InteractionResult redeemHeart(ServerPlayer p, ItemStack held) {
        if (p.isSpectator()) return InteractionResult.PASS;
        var maxHealth = p.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null || maxHealth.getBaseValue() >= maxHearts * 2.0) {
            tell(p, "You have reached the " + maxHearts + "-heart maximum.");
            return InteractionResult.FAIL;
        }
        maxHealth.setBaseValue(maxHealth.getBaseValue() + 2);
        if (!p.getAbilities().instabuild) held.shrink(1);
        tell(p, "Redeemed one Heart.");
        return InteractionResult.SUCCESS;
    }

    /** Whole-number currency amounts, with optional case-insensitive K/M/B/T suffix.
     *  Fractional abbreviations such as 1.5m are allowed if they yield a whole unit.
     */
    private static long parseShortAmount(String input) {
        if(input==null || !input.matches("(?i)\\d+(?:\\.\\d+)?[kmbt]?"))
            throw new IllegalArgumentException("Invalid amount. Use 1000, 1k, 1m, 1b or 1t.");
        String normalized = input.toLowerCase(java.util.Locale.ROOT);
        char suffix = normalized.charAt(normalized.length()-1);
        long multiplier = switch(suffix) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b' -> 1_000_000_000L;
            case 't' -> 1_000_000_000_000L;
            default -> 1L;
        };
        String number = multiplier==1L ? normalized : normalized.substring(0,normalized.length()-1);
        try {
            long amount = new java.math.BigDecimal(number)
                .multiply(java.math.BigDecimal.valueOf(multiplier))
                .longValueExact();
            if(amount<=0)throw new IllegalArgumentException("Amount must be greater than zero.");
            return amount;
        }catch(ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("Amount must be a whole number and not exceed the maximum balance.");
        }
    }
    private static int giveCurrencyShort(net.minecraft.commands.CommandSourceStack source,
                                         ServerPlayer target,String input,
                                         Map<UUID,Long> currency,String label) {
        long amount;
        try { amount = parseShortAmount(input); }
        catch(IllegalArgumentException ex) {
            source.sendSystemMessage(Component.literal(ex.getMessage()));
            return 0;
        }
        return giveCurrency(source,target,amount,currency,label);
    }

    private static int giveCurrency(net.minecraft.commands.CommandSourceStack source, ServerPlayer target,
                                    long amount, Map<UUID, Long> currency, String label) {
        long current = currency.getOrDefault(target.getUUID(), 0L);
        if (amount > Long.MAX_VALUE - current) {
            source.sendSystemMessage(Component.literal("Grant rejected: balance would overflow."));
            return 0;
        }
        currency.put(target.getUUID(), current + amount);
        target.sendSystemMessage(Component.literal("You received " + amount + " " + label + " from an admin."));
        source.sendSystemMessage(Component.literal("Gave " + amount + " " + label + " to "
                + target.getName().getString() + ". New balance: " + (current + amount)));
        return 1;
    }
    private static int balance(ServerPlayer p) { return tell(p, "Balance: $"+BALANCE.getOrDefault(p.getUUID(),0L)); }
    private static int tell(ServerPlayer p, String message) { p.sendSystemMessage(Component.literal(message)); return 1; }
}
