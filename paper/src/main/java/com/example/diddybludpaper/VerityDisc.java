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

/**
 * Collectible custom music disc. The original recording must be provided with
 * appropriate redistribution permission before playable audio is enabled.
 */
final class VerityDisc implements Listener {
    private final NamespacedKey key;
    VerityDisc(DiddybludPaper plugin){
        key=new NamespacedKey(plugin,"verity_disc");
    }
    ItemStack item(){
        ItemStack disc=new ItemStack(Material.MUSIC_DISC_13);
        ItemMeta meta=disc.getItemMeta();
        meta.setDisplayName("§d§lIt's Me, It's Verity");
        meta.setLore(List.of(
            "§7Custom Diddyblud SMP music disc",
            "§7Track: It's Me, It's Verity",
            "§eAudio pack not installed yet",
            "§7Requires authorized song audio"
        ));
        meta.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
        disc.setItemMeta(meta);
        return disc;
    }
    @EventHandler(ignoreCancelled=true)
    public void onUse(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Block block=event.getClickedBlock();
        if(block==null||block.getType()!=Material.JUKEBOX)return;
        Player player=event.getPlayer();
        ItemStack held=player.getInventory().getItemInMainHand();
        if(held==null||!held.hasItemMeta()||
            !Byte.valueOf((byte)1).equals(held.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.BYTE)))return;
        // Avoid playing the vanilla MUSIC_DISC_13 recording under the Verity title.
        event.setCancelled(true);
        player.sendMessage("§eThe Verity disc is in /customitems, but the song audio has not been added yet.");
        player.sendMessage("§7An authorized .ogg recording and a resource pack are needed for playback.");
    }
}
