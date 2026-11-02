package com.example.diddybludpaper;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Physical, stackable banknotes. Only items signed by this plugin can be deposited. */
final class MoneyBills implements Listener {
    private static final long[] VALUES={1_000_000_000L,100_000_000L,1_000_000L,100_000L,10_000L,1_000L,100L,10L,1L};
    private final DiddybludPaper plugin;
    private final NamespacedKey key;
    MoneyBills(DiddybludPaper plugin){this.plugin=plugin;this.key=new NamespacedKey(plugin,"bill_value");}

    private ItemStack note(long value,int amount){
        ItemStack item=new ItemStack(Material.PAPER,amount);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName("§a§lDiddyblud SMP §f$"+String.format(Locale.US,"%,d",value));
        String suffix=value>=1_000_000_000L?"1b":value>=100_000_000L?"100m":value>=1_000_000L?"1m":value>=100_000L?"100k":value>=10_000L?"10k":value>=1_000L?"1k":String.valueOf(value);
        meta.setItemModel(new NamespacedKey("diddyblud","bill_"+suffix));
        meta.setLore(List.of("§7Official Diddyblud SMP banknote","§eValue: §f$"+String.format(Locale.US,"%,d",value),
            "§7Right-click to redeem this stack or use §f/money deposit§7."));
        meta.getPersistentDataContainer().set(key,PersistentDataType.LONG,value);
        item.setItemMeta(meta);
        return item;
    }
    private long denomination(ItemStack item){
        if(item==null||item.getType()!=Material.PAPER||!item.hasItemMeta())return 0;
        Long value=item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.LONG);
        if(value==null)return 0;
        for(long denom:VALUES)if(denom==value)return value;
        return 0;
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void redeemOnRightClick(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND)return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR&&event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        Player p=event.getPlayer();
        ItemStack held=p.getInventory().getItemInMainHand();
        long value=denomination(held);
        if(value==0)return;
        event.setCancelled(true);
        long amount=value*held.getAmount();
        if(!plugin.credit(p.getUniqueId(),amount)){
            p.sendMessage("§cYour balance cannot hold this amount.");return;
        }
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        p.sendMessage("§aRedeemed §f$"+String.format(Locale.US,"%,d",amount)+"§a from your bills.");
    }

    // Add loot once when a generated container's loot table is resolved.
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true)
    public void onChestLoot(LootGenerateEvent event){
        if(!(event.getInventoryHolder() instanceof org.bukkit.block.Chest)
            && !(event.getInventoryHolder() instanceof org.bukkit.block.Barrel)
            && !(event.getInventoryHolder() instanceof org.bukkit.block.ShulkerBox))return;
        if(java.util.concurrent.ThreadLocalRandom.current().nextDouble()>=0.28)return;
        double roll=java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        long value=roll<0.45?1L:roll<0.70?10L:roll<0.84?100L:
                   roll<0.93?1_000L:roll<0.976?10_000L:
                   roll<0.995?100_000L:roll<0.9997?1_000_000L:roll<0.99997?100_000_000L:1_000_000_000L;
        int count=value<=10?1+java.util.concurrent.ThreadLocalRandom.current().nextInt(4):1;
        event.getLoot().add(note(value,count));
    }

    void withdraw(Player player,long amount){
        if(amount<=0){player.sendMessage("§cAmount must be positive.");return;}
        if(plugin.funds(player.getUniqueId())<amount){player.sendMessage("§cNot enough money.");return;}
        List<ItemStack> notes=new ArrayList<>();
        long remaining=amount;
        for(long value:VALUES){
            long count=remaining/value;remaining%=value;
            while(count>0){
                int stack=(int)Math.min(64L,count);
                notes.add(note(value,stack));
                count-=stack;
                if(notes.size()>2304){player.sendMessage("§cToo many bills for one withdrawal. Use a smaller amount.");return;}
            }
        }
        // Simulate a full insertion (including merging into existing stacks) before charging.
        ItemStack[] simulation=player.getInventory().getStorageContents();
        Inventory shadow=Bukkit.createInventory(null,36);
        shadow.setContents(simulation);
        if(!shadow.addItem(notes.toArray(new ItemStack[0])).isEmpty()){
            player.sendMessage("§cNot enough inventory space for these bills.");return;
        }
        if(!plugin.charge(player.getUniqueId(),amount)){player.sendMessage("§cWithdrawal failed.");return;}
        Map<Integer,ItemStack> leftovers=player.getInventory().addItem(notes.toArray(new ItemStack[0]));
        if(!leftovers.isEmpty()){
            // Extremely defensive rollback: remove the inserted notes and return money.
            for(ItemStack item:notes)player.getInventory().removeItem(item);
            plugin.credit(player.getUniqueId(),amount);
            player.sendMessage("§cInventory changed. Withdrawal cancelled.");
            return;
        }
        player.sendMessage("§aWithdrew §f$"+String.format(Locale.US,"%,d",amount)+"§a as §f"+notes.size()+"§a bill stacks.");
    }
    void deposit(Player player){
        long total=0;
        for(ItemStack stack:player.getInventory().getStorageContents()){
            long value=denomination(stack);
            if(value==0)continue;
            long sum=value*stack.getAmount();
            if(total>Long.MAX_VALUE-sum){player.sendMessage("§cToo many bills to deposit at once.");return;}
            total+=sum;
        }
        if(total==0){player.sendMessage("§cYou have no banknotes to deposit.");return;}
        if(plugin.funds(player.getUniqueId())>Long.MAX_VALUE-total){
            player.sendMessage("§cYour balance is too large for this deposit.");return;
        }
        if(!plugin.credit(player.getUniqueId(),total)){player.sendMessage("§cDeposit failed.");return;}
        ItemStack[] storage=player.getInventory().getStorageContents();
        for(int i=0;i<storage.length;i++)if(denomination(storage[i])!=0)storage[i]=null;
        player.getInventory().setStorageContents(storage);
        player.sendMessage("§aDeposited §f$"+String.format(Locale.US,"%,d",total)+"§a into your balance.");
    }
}
