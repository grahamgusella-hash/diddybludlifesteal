package com.example.diddybludpaper;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Protected chest selling tool; unsellable items remain in place. */
final class SellAxe implements Listener {
    private final DiddybludPaper plugin;
    private final NamespacedKey identity;
    SellAxe(DiddybludPaper plugin){
        this.plugin=plugin;
        identity=new NamespacedKey(plugin,"sell_axe");
    }
    ItemStack item(){
        ItemStack stack=new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta=stack.getItemMeta();
        meta.setDisplayName("§6§lSell Axe");
        meta.setLore(List.of("§7Right-click a chest to sell its contents",
            "§7Only items with an active shop sell price are sold",
            "§aThe chest and unsellable items stay intact"));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(identity,PersistentDataType.BYTE,(byte)1);
        stack.setItemMeta(meta);
        return stack;
    }
    private boolean isSellAxe(ItemStack item){
        return item!=null&&item.hasItemMeta()&&
            Byte.valueOf((byte)1).equals(item.getItemMeta().getPersistentDataContainer().get(identity,PersistentDataType.BYTE));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void protectChest(BlockBreakEvent event){
        if(isSellAxe(event.getPlayer().getInventory().getItemInMainHand())&&
            event.getBlock().getState() instanceof Chest)event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void sellChest(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        if(!isSellAxe(event.getPlayer().getInventory().getItemInMainHand()))return;
        Block clicked=event.getClickedBlock();
        if(clicked==null||!(clicked.getState() instanceof Chest chest))return;
        event.setCancelled(true);
        Player player=event.getPlayer();
        Inventory inventory=chest.getInventory();
        ItemStack[] original=inventory.getContents();
        ItemStack[] remaining=original.clone();
        long total=0;
        int count=0;
        for(int i=0;i<original.length;i++){
            ItemStack stack=original[i];
            if(stack==null||stack.getType().isAir())continue;
            // Prevent special named/plugin items from being sold as ordinary materials.
            if(stack.hasItemMeta())continue;
            long price=plugin.sellPrice(stack.getType().name());
            if(price<=0)continue;
            if(price>Long.MAX_VALUE/stack.getAmount() ||
               total>Long.MAX_VALUE-price*stack.getAmount()){
                player.sendMessage("§cChest value too large to sell.");return;
            }
            total+=price*stack.getAmount();
            count+=stack.getAmount();
            remaining[i]=null;
        }
        if(total==0){
            player.sendMessage("§eNo sellable items found in this chest.");return;
        }
        if(!plugin.credit(player.getUniqueId(),total)){
            player.sendMessage("§cYour balance cannot hold this sale.");return;
        }
        inventory.setContents(remaining);
        player.sendMessage("§aSold §f"+count+"§a items for §6$"+
            String.format(Locale.US,"%,d",total)+"§a. Chest preserved.");
    }
}
