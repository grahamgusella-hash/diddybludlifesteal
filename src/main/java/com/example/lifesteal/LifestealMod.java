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
    static Map<String, Long> shopPrices() { return SHOP; }

    @Override public void onInitialize() {
        SmpMenus.init();
        Identifier heartId = Identifier.fromNamespaceAndPath(MODID, "heart");
        HEART = Registry.register(BuiltInRegistries.ITEM, heartId, new Item(new Item.Properties().setId(ResourceKey.create(BuiltInRegistries.ITEM.key(), heartId))) {
            @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
                if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                    var attribute = sp.getAttribute(Attributes.MAX_HEALTH);
                    if (attribute == null || attribute.getBaseValue() >= 40) {
                        sp.sendSystemMessage(Component.literal("You have reached the 20-heart limit."));
                        return InteractionResult.FAIL;
                    }
                    attribute.setBaseValue(attribute.getBaseValue() + 2);
                    if (!sp.getAbilities().instabuild) sp.getItemInHand(hand).shrink(1);
                    sp.sendSystemMessage(Component.literal("Redeemed one heart!"));
                }
                return InteractionResult.SUCCESS;
            }
        });
        Identifier tabId = Identifier.fromNamespaceAndPath(MODID, "custom");
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ResourceKey.create(BuiltInRegistries.CREATIVE_MODE_TAB.key(), tabId),
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0).icon(() -> new ItemStack(HEART))
                        .title(Component.translatable("itemGroup.lifesteal.custom"))
                        .displayItems((params, output) -> {
                            output.accept(HEART);
                            // Visual prototype spawners. Mob type is not retained on placement.
                            for (String mob : List.of("zombie", "skeleton", "spider", "creeper", "enderman",
                                    "blaze", "pig", "cow", "sheep", "chicken", "iron_golem")) {
                                ItemStack spawner = new ItemStack(Items.SPAWNER);
                                spawner.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                                        Component.literal(mob + " Spawner"));
                                output.accept(spawner);
                            }
                        }).build());
        SHOP.put("minecraft:diamond", 500L);
        SHOP.put("minecraft:netherite_ingot", 5000L);
        SHOP.put("minecraft:golden_apple", 800L);
        SHOP.put("lifesteal:heart", 10000L);

        ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
            if (!(victim instanceof ServerPlayer loser) || !(source.getEntity() instanceof ServerPlayer winner)) return;
            if (loser.getUUID().equals(winner.getUUID())) return;
            var l = loser.getAttribute(Attributes.MAX_HEALTH);
            var w = winner.getAttribute(Attributes.MAX_HEALTH);
            if (l == null || w == null || l.getBaseValue() <= 2 || w.getBaseValue() >= 40) return;
            l.setBaseValue(l.getBaseValue() - 2);
            w.setBaseValue(w.getBaseValue() + 2);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(literal("withdraw").then(argument("amount", IntegerArgumentType.integer(1, 19))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                    var health = p.getAttribute(Attributes.MAX_HEALTH);
                    if (health == null || health.getBaseValue() - amount * 2 < 2) return tell(p, "You must keep at least one heart.");
                    ItemStack stack = new ItemStack(HEART, amount);
                    if (p.getInventory().getFreeSlot() < 0) return tell(p, "Make room in your inventory first.");
                    health.setBaseValue(health.getBaseValue() - amount * 2);
                    p.getInventory().add(stack);
                    return tell(p, "Withdrew " + amount + " heart(s).");
                })));
            dispatcher.register(literal("hearts").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                return tell(p, "Hearts: " + (int)(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue()/2));
            }));
            dispatcher.register(literal("balance").executes(ctx -> balance(ctx.getSource().getPlayerOrException())));
            dispatcher.register(literal("bal").executes(ctx -> balance(ctx.getSource().getPlayerOrException())));
            dispatcher.register(literal("shards").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                return tell(p, "Shards: " + SHARDS.getOrDefault(p.getUUID(), 0L));
            }).then(literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(argument("player", EntityArgument.player())
                .then(argument("amount", LongArgumentType.longArg(1))
                .executes(ctx -> giveCurrency(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                    LongArgumentType.getLong(ctx, "amount"), SHARDS, "shards"))))));
            dispatcher.register(literal("money")
                .then(literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(argument("player", EntityArgument.player())
                .then(argument("amount", LongArgumentType.longArg(1))
                .executes(ctx -> giveCurrency(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                    LongArgumentType.getLong(ctx, "amount"), BALANCE, "money"))))));
            dispatcher.register(literal("pay").then(argument("player", EntityArgument.player()).then(argument("amount", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    long amt = IntegerArgumentType.getInteger(ctx, "amount");
                    if (p.getUUID().equals(target.getUUID())) return tell(p, "You cannot pay yourself.");
                    long funds = BALANCE.getOrDefault(p.getUUID(), 0L);
                    if (funds < amt) return tell(p, "Not enough money.");
                    BALANCE.put(p.getUUID(), funds - amt);
                    BALANCE.merge(target.getUUID(), amt, Long::sum);
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
                    return tell(ctx.getSource().getPlayerOrException(),"Changed session shop price for "+id+" to $"+price);
                })))));
        });
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
