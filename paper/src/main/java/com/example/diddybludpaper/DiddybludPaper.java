package com.example.diddybludpaper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/** Early Paper port. GUI is a placeholder until shop and spawner systems are migrated. */
public final class DiddybludPaper extends JavaPlugin implements Listener {
    private static final String SHOP_TITLE = "Shop | Menu";
    private final Map<UUID, Long> balances = new HashMap<>();
    private File balancesFile;

    @Override public void onEnable() {
        saveDefaultConfig();
        balancesFile = new File(getDataFolder(), "balances.yml");
        loadBalances();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Paper port started. Shop, hearts and virtual spawners still require migration.");
    }
    @Override public void onDisable() { saveBalances(); }

    private void loadBalances() {
        if (!balancesFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(balancesFile);
        if (yaml.getConfigurationSection("players") == null) return;
        for (String id : yaml.getConfigurationSection("players").getKeys(false)) {
            try {
                long amount = yaml.getLong("players." + id);
                if (amount >= 0) balances.put(UUID.fromString(id), amount);
            } catch (IllegalArgumentException ignored) { }
        }
    }
    private void saveBalances() {
        YamlConfiguration yaml = new YamlConfiguration();
        balances.forEach((uuid, amount) -> yaml.set("players." + uuid, amount));
        try {
            getDataFolder().mkdirs();
            yaml.save(balancesFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save balances.yml: " + e.getMessage());
        }
    }
    private static long amount(String input) {
        if (!input.matches("(?i)\\d+(?:\\.\\d+)?[kmbt]?"))
            throw new IllegalArgumentException("Use an amount like 100, 1k, 2m, 1b or 1t.");
        String text = input.toLowerCase(java.util.Locale.ROOT);
        char suffix = text.charAt(text.length()-1);
        long mult = switch (suffix) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b' -> 1_000_000_000L;
            case 't' -> 1_000_000_000_000L;
            default -> 1L;
        };
        String digits = mult==1 ? text : text.substring(0,text.length()-1);
        try {
            long parsed = new BigDecimal(digits).multiply(BigDecimal.valueOf(mult)).longValueExact();
            if (parsed <= 0) throw new IllegalArgumentException("Amount must be positive.");
            return parsed;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Amount is not a valid whole number.");
        }
    }
    private static ItemStack icon(Material material,String label) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(label);
            stack.setItemMeta(meta);
        }
        return stack;
    }
    private void showShop(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, SHOP_TITLE);
        inv.setItem(11, icon(Material.SPAWNER, "Spawners — Paper migration pending"));
        inv.setItem(12, icon(Material.TOTEM_OF_UNDYING, "Combat — Paper migration pending"));
        inv.setItem(13, icon(Material.ENCHANTED_BOOK, "Enchantments — Paper migration pending"));
        inv.setItem(14, icon(Material.DIAMOND, "Shard Shop — Paper migration pending"));
        inv.setItem(20, icon(Material.BLAZE_POWDER, "Mob Drops — Paper migration pending"));
        inv.setItem(21, icon(Material.OAK_LOG, "Blocks — Paper migration pending"));
        inv.setItem(22, icon(Material.CARROT, "Farming — Paper migration pending"));
        inv.setItem(23, icon(Material.CHEST, "Storage — Paper migration pending"));
        inv.setItem(24, icon(Material.REDSTONE, "Redstone — Paper migration pending"));
        player.openInventory(inv);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!SHOP_TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendMessage("Shop purchases aren't ported to Paper yet.");
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        String name = cmd.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("diddyblud")) {
            sender.sendMessage("Diddyblud Paper 26.3 migration starter: economy working; shop preview only.");
            return true;
        }
        if (name.equals("shop")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the GUI."); return true;
            }
            showShop(player);
            return true;
        }
        if (name.equals("bal")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can check their balance."); return true;
            }
            sender.sendMessage("Balance: $" + balances.getOrDefault(player.getUniqueId(),0L));
            return true;
        }
        if (name.equals("money")) {
            if (!sender.hasPermission("diddyblud.admin")) {
                sender.sendMessage("Only OPs can give money."); return true;
            }
            if (args.length!=3 || !args[0].equalsIgnoreCase("give")) {
                sender.sendMessage("Use /money give <player> <amount>.");return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage("Player must be online.");return true; }
            try {
                long value=amount(args[2]);
                long old=balances.getOrDefault(target.getUniqueId(),0L);
                long next=Math.addExact(old,value);
                balances.put(target.getUniqueId(),next);
                saveBalances();
                sender.sendMessage("Gave $"+value+" to "+target.getName()+".");
                target.sendMessage("You received $"+value+".");
            } catch (IllegalArgumentException | ArithmeticException e) {
                sender.sendMessage("Invalid amount or balance overflow.");
            }
            return true;
        }
        if (name.equals("pay")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can pay.");return true;
            }
            if (args.length!=2) { sender.sendMessage("Use /pay <player> <amount>.");return true; }
            Player target=Bukkit.getPlayerExact(args[0]);
            if (target==null||target.getUniqueId().equals(player.getUniqueId())) {
                sender.sendMessage("Choose another online player.");return true;
            }
            try {
                long value=amount(args[1]);
                long from=balances.getOrDefault(player.getUniqueId(),0L);
                long to=balances.getOrDefault(target.getUniqueId(),0L);
                if (from<value) { sender.sendMessage("Not enough money.");return true; }
                long credited=Math.addExact(to,value);
                balances.put(player.getUniqueId(),from-value);
                balances.put(target.getUniqueId(),credited);
                saveBalances();
                sender.sendMessage("Paid $"+value+" to "+target.getName()+".");
                target.sendMessage("Received $"+value+" from "+player.getName()+".");
            } catch (IllegalArgumentException | ArithmeticException e) {
                sender.sendMessage("Invalid amount or recipient balance overflow.");
            }
            return true;
        }
        return false;
    }
}
