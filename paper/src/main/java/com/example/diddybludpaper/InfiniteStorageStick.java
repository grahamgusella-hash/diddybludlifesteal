package com.example.diddybludpaper;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Owner-controlled infinite supply for placed container inventories while holding the stick. */
final class InfiniteStorageStick implements Listener {
    private final DiddybludPaper plugin;
    private final NamespacedKey key;
    private final Map<String,UUID> owners=new HashMap<>();
    private final File file;
    InfiniteStorageStick(DiddybludPaper plugin){
        this.plugin=plugin;
        key=new NamespacedKey(plugin,"infinite_storage_stick");
        file=new File(plugin.getDataFolder(),"storage-owners.yml");
        YamlConfiguration data=YamlConfiguration.loadConfiguration(file);
        for(String path:data.getKeys(false)){
            try { owners.put(path,UUID.fromString(data.getString(path))); }
            catch(Exception ignored){}
        }
    }
    ItemStack item(){
        ItemStack stick=new ItemStack(Material.STICK);
        ItemMeta meta=stick.getItemMeta();
        meta.setDisplayName("§b§lInfinite Storage Stick");
        meta.setLore(List.of("§7Hold to make your placed storage infinite",
            "§7Chest, barrel, hopper, shulker and other containers",
            "§aStop holding to disable the effect"));
        meta.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
        stick.setItemMeta(meta);return stick;
    }
    private boolean holding(Player player){
        ItemStack held=player.getInventory().getItemInMainHand();
        return held!=null&&held.hasItemMeta()&&Byte.valueOf((byte)1).equals(
            held.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.BYTE));
    }
    private String id(Block block){
        return block.getWorld().getUID().toString()+"_"+block.getX()+"_"+block.getY()+"_"+block.getZ();
    }
    private void save(){
        YamlConfiguration data=new YamlConfiguration();
        owners.forEach((k,v)->data.set(k,v.toString()));
        try {data.save(file);}catch(IOException e){plugin.getLogger().warning("Could not save storage ownership: "+e.getMessage());}
    }
    @EventHandler(ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent event){
        if(event.getBlockPlaced().getState() instanceof Container){
            owners.put(id(event.getBlockPlaced()),event.getPlayer().getUniqueId());save();
        }
    }
    @EventHandler(ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){
        if(owners.remove(id(event.getBlock()))!=null)save();
    }
    private Player activeOwner(Inventory inventory){
        InventoryHolder holder=inventory.getHolder();
        if(!(holder instanceof BlockState state))return null;
        UUID owner=owners.get(id(state.getBlock()));
        if(owner==null)return null;
        Player player=Bukkit.getPlayer(owner);
        return player!=null&&player.isOnline()&&holding(player)?player:null;
    }
    private ItemStack[] cloneItems(Inventory inventory){
        ItemStack[] original=inventory.getContents();
        ItemStack[] copy=new ItemStack[original.length];
        for(int i=0;i<copy.length;i++)copy[i]=original[i]==null?null:original[i].clone();
        return copy;
    }
    private void restore(Inventory inventory,ItemStack[] previous,UUID owner){
        Bukkit.getScheduler().runTask(plugin,()->{
            Player player=Bukkit.getPlayer(owner);
            if(player==null||!holding(player)||activeOwner(inventory)==null)return;
            ItemStack[] current=inventory.getContents();
            for(int i=0;i<previous.length&&i<current.length;i++){
                ItemStack old=previous[i],now=current[i];
                if(old==null||old.getType().isAir())continue;
                if(now==null||now.getType().isAir()){current[i]=old;continue;}
                if(now.isSimilar(old)&&now.getAmount()<old.getAmount())current[i]=old;
            }
            inventory.setContents(current);
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onClick(InventoryClickEvent event){
        Inventory top=event.getView().getTopInventory();
        Player owner=activeOwner(top);
        if(owner==null)return;
        if(event.getRawSlot()>=top.getSize()&&event.getAction()!=InventoryAction.MOVE_TO_OTHER_INVENTORY)return;
        if(event.getWhoClicked() instanceof Player clicker && !clicker.getUniqueId().equals(owner.getUniqueId()))return;
        restore(top,cloneItems(top),owner.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onDrag(InventoryDragEvent event){
        Inventory top=event.getView().getTopInventory();
        Player owner=activeOwner(top);
        if(owner!=null)restore(top,cloneItems(top),owner.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onHopperMove(InventoryMoveItemEvent event){
        Inventory source=event.getSource();
        Player owner=activeOwner(source);
        if(owner!=null)restore(source,cloneItems(source),owner.getUniqueId());
    }
}
