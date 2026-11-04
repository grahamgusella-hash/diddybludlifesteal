package com.example.diddybludpaper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diddyblud disc, played from the separately distributed audio resource pack.
 * Keeps the jukebox empty rather than inserting a vanilla disc and playing its
 * original (incorrect) recording. Right-click the same jukebox to stop.
 */
final class VerityDisc implements Listener {
    private static final String SOUND="diddyblud:verity";
    private static final long DURATION_TICKS=2_520L; // Approx. 123-second source + buffer
    private final DiddybludPaper plugin;
    private final NamespacedKey key;
    private final Map<Location,BukkitTask> active=new HashMap<>();

    VerityDisc(DiddybludPaper plugin){
        this.plugin=plugin;
        key=new NamespacedKey(plugin,"verity_disc");
    }

    ItemStack item(){
        ItemStack disc=new ItemStack(Material.MUSIC_DISC_13);
        ItemMeta meta=disc.getItemMeta();
        meta.setDisplayName("§d§lIt's Me, It's Verity");
        meta.setLore(List.of(
            "§7Horror Skunx - It's Me, It's Verity",
            "§7Diddyblud SMP custom music disc",
            "§aRight-click a jukebox to play or stop",
            "§7Requires the Diddyblud Verity resource pack"
        ));
        meta.setItemModel(new NamespacedKey("diddyblud","verity_disc"));
        meta.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
        disc.setItemMeta(meta);
        return disc;
    }

    private boolean isVerity(ItemStack held){
        return held!=null&&held.hasItemMeta()&&
            Byte.valueOf((byte)1).equals(
                held.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.BYTE));
    }

    private void stop(Location location){
        BukkitTask scheduled=active.remove(location);
        if(scheduled==null)return;
        scheduled.cancel();
        for(Player viewer:location.getWorld().getPlayers()){
            if(viewer.getLocation().distanceSquared(location)<=96*96)
                viewer.stopSound(SOUND,SoundCategory.RECORDS);
        }
    }

    @EventHandler(ignoreCancelled=true)
    public void onUse(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Block block=event.getClickedBlock();
        if(block==null||block.getType()!=Material.JUKEBOX)return;
        Location location=block.getLocation();
        Player player=event.getPlayer();

        if(active.containsKey(location)){
            // An empty hand can also stop a previously started song.
            event.setCancelled(true);
            stop(location);
            player.sendMessage("§eStopped It's Me, It's Verity.");
            return;
        }
        if(!isVerity(player.getInventory().getItemInMainHand()))return;
        event.setCancelled(true);

        if(block.getState() instanceof Jukebox jukebox && jukebox.hasRecord()){
            player.sendMessage("§eRemove the existing disc first.");
            return;
        }

        // The sound is supplied by the separate optional player resource pack.
        block.getWorld().playSound(location.clone().add(0.5,0.5,0.5),
            SOUND,SoundCategory.RECORDS,4.0f,1.0f);
        BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,
            ()->active.remove(location),DURATION_TICKS);
        active.put(location,task);
        player.sendMessage("§dNow playing: §fIt's Me, It's Verity");
    }

    @EventHandler(ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){
        if(event.getBlock().getType()==Material.JUKEBOX)
            stop(event.getBlock().getLocation());
    }

    void close(){
        for(Location location:List.copyOf(active.keySet()))stop(location);
    }
}
