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

/** Custom Lord Verity disc, whose audio is supplied by the combined resource pack. */
final class LordVerityDisc implements Listener {
    private static final String SOUND="diddyblud:lord_verity";
    // Uploaded file is approximately 141 seconds; allow a short buffer.
    private static final long DURATION_TICKS=2_860L;
    private final DiddybludPaper plugin;
    private final NamespacedKey identity;
    private final Map<Location,BukkitTask> active=new HashMap<>();
    private final VerityDisc other;

    LordVerityDisc(DiddybludPaper plugin,VerityDisc other){
        this.plugin=plugin;
        this.other=other;
        identity=new NamespacedKey(plugin,"lord_verity_disc");
    }

    ItemStack item(){
        ItemStack disc=new ItemStack(Material.MUSIC_DISC_13);
        ItemMeta meta=disc.getItemMeta();
        meta.setDisplayName("§5§lLord Verity - Master of Humanity");
        meta.setLore(List.of(
            "§7Horror Skunx - Master of Humanity (Lord Verity)",
            "§7Diddyblud SMP custom music disc",
            "§aRight-click a jukebox to play or stop",
            "§7Requires the Diddyblud complete resource pack"
        ));
        meta.setItemModel(new NamespacedKey("diddyblud","lord_verity_disc"));
        meta.getPersistentDataContainer().set(identity,PersistentDataType.BYTE,(byte)1);
        disc.setItemMeta(meta);
        return disc;
    }

    private boolean isDisc(ItemStack held){
        return held!=null&&held.hasItemMeta()&&Byte.valueOf((byte)1).equals(
            held.getItemMeta().getPersistentDataContainer().get(identity,PersistentDataType.BYTE));
    }

    boolean stopAt(Location location){
        BukkitTask task=active.remove(location);
        if(task==null)return false;
        task.cancel();
        if(location.getWorld()!=null)
            for(Player listener:location.getWorld().getPlayers()){
                if(listener.getLocation().distanceSquared(location)<=96*96)
                    listener.stopSound(SOUND,SoundCategory.RECORDS);
            }
        return true;
    }

    @EventHandler(ignoreCancelled=true)
    public void onJukebox(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Block target=event.getClickedBlock();
        if(target==null||target.getType()!=Material.JUKEBOX)return;
        Location location=target.getLocation();
        Player player=event.getPlayer();
        if(active.containsKey(location)){
            event.setCancelled(true);
            stopAt(location);
            player.sendMessage("§eStopped Lord Verity.");
            return;
        }
        if(!isDisc(player.getInventory().getItemInMainHand()))return;
        event.setCancelled(true);
        if(target.getState() instanceof Jukebox jukebox && jukebox.hasRecord()){
            player.sendMessage("§eRemove the existing disc first.");return;
        }
        // Prevent two custom tracks playing from the same jukebox.
        other.stopAt(location);
        target.getWorld().playSound(location.clone().add(0.5,0.5,0.5),
            SOUND,SoundCategory.RECORDS,4.0f,1.0f);
        BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,
            ()->active.remove(location),DURATION_TICKS);
        active.put(location,task);
        player.sendMessage("§dNow playing: §fLord Verity - Master of Humanity");
    }

    @EventHandler(ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){
        if(event.getBlock().getType()==Material.JUKEBOX)
            stopAt(event.getBlock().getLocation());
    }
    void close(){for(Location location:List.copyOf(active.keySet()))stopAt(location);}
}
