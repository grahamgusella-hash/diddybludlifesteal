package com.example.diddybludpaper;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Shift-to-mine connected ores or fell connected tree logs. */
public final class MiningAbilities implements Listener {
    private final DiddybludPaper plugin;
    private boolean processing = false;
    public MiningAbilities(DiddybludPaper plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (processing || !event.getPlayer().isSneaking()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        Block first = event.getBlock();
        Material type = first.getType();
        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean tree = isLog(type) && (isAxe(tool) || isEmptyHand(tool));
        boolean ore = isOre(type) && isPickaxe(tool);
        if (!tree && !ore) return;
        if (!plugin.abilityAllowed(player, tree ? "treefeller" : "veinminer")) return;

        int max = tree ? 96 : 64;
        int[][] offsets = tree ? NEIGHBORS_26 : NEIGHBORS_6;
        Set<String> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(first);
        processing = true;
        try {
            while (!queue.isEmpty() && visited.size() < max) {
                Block block = queue.removeFirst();
                String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
                if (!visited.add(key) || block.getType() != type) continue;
                if (block != first) {
                    if (!plugin.abilityAllowed(player, tree ? "treefeller" : "veinminer")) break;
                    BlockBreakEvent extra = new BlockBreakEvent(block, player);
                    Bukkit.getPluginManager().callEvent(extra);
                    if (extra.isCancelled()) continue;
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (!tree && !isPickaxe(held)) break;
                    if (tree && !(isAxe(held) || isEmptyHand(held))) break;
                    if (held.getType() == Material.AIR) block.breakNaturally();
                    else block.breakNaturally(held);
                    if (held.getType() != Material.AIR && held.getItemMeta() instanceof Damageable damageable
                            && !damageable.isUnbreakable()) {
                        int unbreaking = held.getEnchantmentLevel(Enchantment.UNBREAKING);
                        if (unbreaking == 0 || java.util.concurrent.ThreadLocalRandom.current().nextInt(unbreaking + 1) == 0) {
                            int next = damageable.getDamage() + 1;
                            if (next >= held.getType().getMaxDurability()) {
                                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                                break;
                            }
                            damageable.setDamage(next);
                            held.setItemMeta(damageable);
                        }
                    }
                }
                for (int[] d : offsets) {
                    Block other = block.getRelative(d[0],d[1],d[2]);
                    if (other.getType() == type && !visited.contains(other.getX()+":"+other.getY()+":"+other.getZ()))
                        queue.addLast(other);
                }
            }
        } finally {
            processing = false;
        }
    }

    private static final int[][] NEIGHBORS_6 = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final int[][] NEIGHBORS_26 = make26();
    private static int[][] make26() {
        List<int[]> result = new ArrayList<>();
        for (int y=0;y<=1;y++) for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++)
            if (x!=0 || y!=0 || z!=0) result.add(new int[]{x,y,z});
        return result.toArray(new int[0][]);
    }
    private static boolean isEmptyHand(ItemStack stack) { return stack == null || stack.getType() == Material.AIR; }
    private static boolean isAxe(ItemStack stack) { return stack != null && stack.getType().name().endsWith("_AXE"); }
    private static boolean isPickaxe(ItemStack stack) { return stack != null && stack.getType().name().endsWith("_PICKAXE"); }
    private static boolean isOre(Material material) {
        String name = material.name();
        return name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
    }
    private static boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }
}
