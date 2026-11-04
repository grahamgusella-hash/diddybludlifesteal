package com.example.diddybludpaper;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;

/** Separate Lord Verity collectible disc. Playback needs the authorized audio file. */
final class LordVerityDisc implements Listener {
    private final NamespacedKey identity;
    LordVerityDisc(DiddybludPaper plugin){
        identity=new NamespacedKey(plugin,"lord_verity_disc");
    }
    ItemStack item(){
        ItemStack disc=new ItemStack(Material.MUSIC_DISC_13);
        ItemMeta meta=disc.getItemMeta();
        meta.setDisplayName("§5§lLord Verity - Master of Humanity");
        meta.setLore(List.of(
            "§7Horror Skunx - Master of Humanity (Lord Verity)",
            "§7Diddyblud SMP custom music disc",
            "§eSong audio not installed yet",
            "§7A separate audio file is required for playback"
        ));
        meta.setItemModel(new NamespacedKey("diddyblud","lord_verity_disc"));
        meta.getPersistentDataContainer().set(identity,PersistentDataType.BYTE,(byte)1);
        disc.setItemMeta(meta);
        return disc;
    }
    @EventHandler(ignoreCancelled=true)
    public void onJukebox(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Block target=event.getClickedBlock();
        if(target==null||target.getType()!=Material.JUKEBOX)return;
        Player player=event.getPlayer();
        ItemStack held=player.getInventory().getItemInMainHand();
        if(held==null||!held.hasItemMeta())return;
        if(!Byte.valueOf((byte)1).equals(held.getItemMeta().getPersistentDataContainer()
                .get(identity,PersistentDataType.BYTE)))return;
        event.setCancelled(true);
        player.sendMessage("§eLord Verity disc added. Upload an audio file to enable the song.");
    }
}
