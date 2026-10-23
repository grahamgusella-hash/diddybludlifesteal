package com.example.diddybludpaper;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Server-side spawners generate virtual drops; no actual mobs emerge. */
public final class PaperSpawners implements Listener {
    public static final String[] MOBS={"blaze","spider","iron_golem","enderman","chicken",
        "zombie","creeper","pig","skeleton"};
    private final DiddybludPaper plugin;
    private final NamespacedKey typeKey;
    private final File file;
    private final Map<String,Stack> placed=new HashMap<>();
    private final Map<String,Long> prices=new HashMap<>();
    private record Stack(String mob,int count,long stored,long xp,UUID owner){}
    private static final Map<String,Material> DROP=Map.of(
        "blaze",Material.BLAZE_ROD,"spider",Material.STRING,
        "iron_golem",Material.IRON_INGOT,"enderman",Material.ENDER_PEARL,
        "chicken",Material.FEATHER,"zombie",Material.ROTTEN_FLESH,
        "creeper",Material.GUNPOWDER,"pig",Material.PORKCHOP,
        "skeleton",Material.BONE);
    private static final Map<String,Long> START_PRICE=Map.of(
        "iron_golem",1000000L,"blaze",600000L,"enderman",500000L,
        "creeper",350000L,"spider",225000L,"skeleton",175000L,
        "zombie",125000L,"chicken",85000L,"pig",60000L);
    private static final Map<String,Long> DROP_SELL=Map.of(
        "iron_golem",25L,"blaze",18L,"enderman",14L,"creeper",12L,
        "spider",7L,"skeleton",6L,"zombie",4L,"chicken",3L,"pig",3L);
    private static String key(Block b){
        return b.getWorld().getUID()+";"+b.getX()+";"+b.getY()+";"+b.getZ();
    }
    private Block locate(String id){
        try{
            String[] s=id.split(";");
            World w=Bukkit.getWorld(UUID.fromString(s[0]));
            if(w==null)return null;
            return w.getBlockAt(Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3]));
        }catch(Exception ignored){return null;}
    }
    public PaperSpawners(DiddybludPaper plugin,NamespacedKey key){
        this.plugin=plugin;typeKey=key;file=new File(plugin.getDataFolder(),"spawners.yml");load();
    }
    public long price(String mob){return prices.getOrDefault(mob,START_PRICE.getOrDefault(mob,100000L));}
    public void adjustPrice(String mob,long delta){
        prices.put(mob,Math.max(0,Math.min(Integer.MAX_VALUE,price(mob)+delta)));save();
    }
    public ItemStack spawner(String mob,int count){
        if(mob==null||!DROP.containsKey(mob))throw new IllegalArgumentException("Unknown spawner.");
        ItemStack item=new ItemStack(Material.SPAWNER,Math.max(1,Math.min(64,count)));
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName("§6"+mob.replace('_',' ')+" Spawner");
        meta.getPersistentDataContainer().set(typeKey,PersistentDataType.STRING,mob);
        item.setItemMeta(meta);return item;
    }
    public String mobFromItem(ItemStack item){
        if(item==null||item.getType()!=Material.SPAWNER||!item.hasItemMeta())return null;
        String mob=item.getItemMeta().getPersistentDataContainer().get(typeKey,PersistentDataType.STRING);
        return mob!=null&&DROP.containsKey(mob)?mob:null;
    }
    public boolean isManaged(Block b){return placed.containsKey(key(b));}
    public void produce(){
        boolean changed=false;
        for(var pair:new ArrayList<>(placed.entrySet())){
            Block block=locate(pair.getKey());
            if(block==null||!block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4))continue;
            if(block.getType()!=Material.SPAWNER)continue;
            Stack s=pair.getValue();
            long multiplier=1+Math.min(50,s.count()/10);
            long quantity=Math.min(1_000_000L,(long)s.count()*multiplier);
            placed.put(pair.getKey(),new Stack(s.mob(),s.count(),
                Math.min(1_000_000_000L,s.stored()+quantity),
                Math.min(100_000_000L,s.xp()+quantity),s.owner()));
            changed=true;
        }
        if(changed)save();
    }
    @EventHandler public void spawn(SpawnerSpawnEvent event){
        if(event.getSpawner()!=null && isManaged(event.getSpawner().getBlock()))event.setCancelled(true);
    }
    // Explosions cannot silently destroy spawner stacks and orphan stored resources.
    @EventHandler(ignoreCancelled=true)
    public void onEntityExplosion(org.bukkit.event.entity.EntityExplodeEvent e){
        e.blockList().removeIf(this::isManaged);
    }
    @EventHandler(ignoreCancelled=true)
    public void onBlockExplosion(org.bukkit.event.block.BlockExplodeEvent e){
        e.blockList().removeIf(this::isManaged);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void rightClick(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND||e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;
        Block clicked=e.getClickedBlock();if(clicked==null)return;
        Player p=e.getPlayer();
        ItemStack item=p.getInventory().getItemInMainHand();
        String mob=mobFromItem(item);
        Stack existing=placed.get(key(clicked));
        if(existing!=null){
            e.setCancelled(true);
            if(mob==null){
                if(item.getType().isAir())openManager(p,clicked);
                else p.sendMessage("§eEmpty your hand to manage the spawner.");
                return;
            }
            if(!existing.mob().equals(mob)){p.sendMessage("§cSpawner types must match.");return;}
            if(!existing.owner().equals(p.getUniqueId())&&!p.hasPermission("diddyblud.admin")){
                p.sendMessage("§cYou do not own this spawner.");return;
            }
            int added=Math.min(10000-existing.count(),p.isSneaking()?item.getAmount():1);
            if(added<=0){p.sendMessage("§cStack is full.");return;}
            placed.put(key(clicked),new Stack(mob,existing.count()+added,existing.stored(),existing.xp(),existing.owner()));
            if(p.getGameMode()!=GameMode.CREATIVE)item.setAmount(item.getAmount()-added);
            save();p.sendMessage("§aSpawner stack: "+(existing.count()+added));return;
        }
        if(mob==null)return;
        if(clicked.getType().isInteractable()&&clicked.getType()!=Material.SPAWNER)return;
        e.setCancelled(true);
        Block target=clicked.getRelative(e.getBlockFace());
        if(!target.isEmpty()||!target.getWorld().getWorldBorder().isInside(target.getLocation()))return;
        target.setType(Material.SPAWNER,false);
        // Native spawner type is irrelevant: all natural spawns are cancelled for managed blocks.
        int added=p.isSneaking()?item.getAmount():1;
        placed.put(key(target),new Stack(mob,added,0,0,p.getUniqueId()));
        if(p.getGameMode()!=GameMode.CREATIVE)item.setAmount(item.getAmount()-added);
        save();p.sendMessage("§aPlaced "+added+" "+mob+" virtual spawner(s).");
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breakSpawner(BlockBreakEvent e){
        Stack s=placed.get(key(e.getBlock()));if(s==null)return;
        Player p=e.getPlayer();
        if(!p.hasPermission("diddyblud.admin")&&!s.owner().equals(p.getUniqueId())){
            p.sendMessage("§cYou do not own this stack.");e.setCancelled(true);return;
        }
        placed.remove(key(e.getBlock()));e.setDropItems(false);e.setExpToDrop(0);
        int count=s.count();while(count>0){
            int n=Math.min(64,count);
            Map<Integer,ItemStack> remaining=p.getInventory().addItem(spawner(s.mob(),n));
            remaining.values().forEach(item->p.getWorld().dropItemNaturally(p.getLocation(),item));
            count-=n;
        }
        save();
    }
    private static final class ManagerHolder implements InventoryHolder {
        final String location;
        private Inventory inventory;
        ManagerHolder(String location){this.location=location;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private void openManager(Player p,Block block){
        Stack s=placed.get(key(block));if(s==null)return;
        if(!s.owner().equals(p.getUniqueId())&&!p.hasPermission("diddyblud.admin")){
            p.sendMessage("§cThis stack belongs to another player.");return;
        }
        ManagerHolder holder=new ManagerHolder(key(block));
        Inventory inv=Bukkit.createInventory(holder,27,"Spawner | "+s.mob());
        holder.inventory=inv;
        inv.setItem(4,icon(Material.SPAWNER,s.mob()+" x"+s.count()));
        inv.setItem(10,icon(Material.CHEST,"Collect "+s.stored()+" drops"));
        inv.setItem(12,icon(Material.EMERALD,"Sell drops for $"+Math.min(Long.MAX_VALUE/2,s.stored()*DROP_SELL.get(s.mob()))));
        inv.setItem(14,icon(Material.EXPERIENCE_BOTTLE,"Collect "+s.xp()+" XP"));
        inv.setItem(16,icon(Material.ANVIL,"Remove 1 spawner from stack"));
        inv.setItem(22,icon(DROP.get(s.mob()),"Stored: "+s.stored()+" | XP: "+s.xp()));
        inv.setItem(26,icon(Material.BARRIER,"Close"));
        p.openInventory(inv);
    }
    private static ItemStack icon(Material material,String name){
        ItemStack s=new ItemStack(material);
        ItemMeta meta=s.getItemMeta();meta.setDisplayName(name);s.setItemMeta(meta);return s;
    }
    @EventHandler public void onMenu(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof ManagerHolder h))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p))return;
        int slot=e.getRawSlot();if(slot>=27||slot<0)return;
        Block block=locate(h.location);if(block==null)return;
        Stack s=placed.get(h.location);if(s==null)return;
        if(!s.owner().equals(p.getUniqueId())&&!p.hasPermission("diddyblud.admin"))return;
        if(slot==26){p.closeInventory();return;}
        if(slot==10&&s.stored()>0){
            long given=0,remaining=s.stored();Material drop=DROP.get(s.mob());
            while(remaining>0&&given<10000){
                int amount=(int)Math.min(64,remaining);
                ItemStack item=new ItemStack(drop,amount);
                Map<Integer,ItemStack> leftovers=p.getInventory().addItem(item);
                int rejected=leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
                int accepted=amount-rejected;
                given+=accepted;remaining-=accepted;
                if(rejected>0)break;
            }
            placed.put(h.location,new Stack(s.mob(),s.count(),s.stored()-given,s.xp(),s.owner()));
        }else if(slot==12&&s.stored()>0){
            long unit=DROP_SELL.get(s.mob()), amount=Math.min(s.stored(),(Long.MAX_VALUE-plugin.funds(p.getUniqueId()))/unit);
            if(amount>0&&plugin.credit(p.getUniqueId(),amount*unit))
                placed.put(h.location,new Stack(s.mob(),s.count(),s.stored()-amount,s.xp(),s.owner()));
        }else if(slot==14&&s.xp()>0){
            int xp=(int)Math.min(s.xp(),100000);
            p.giveExp(xp);
            placed.put(h.location,new Stack(s.mob(),s.count(),s.stored(),s.xp()-xp,s.owner()));
        }else if(slot==16&&s.count()>1){
            Map<Integer,ItemStack> extra=p.getInventory().addItem(spawner(s.mob(),1));
            if(extra.isEmpty())placed.put(h.location,new Stack(s.mob(),s.count()-1,s.stored(),s.xp(),s.owner()));
            else extra.values().forEach(item->p.getWorld().dropItemNaturally(p.getLocation(),item));
        }
        save();openManager(p,block);
    }
    private void load(){
        if(!file.exists())return;
        YamlConfiguration y=YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec=y.getConfigurationSection("placed");
        if(sec!=null)for(String id:sec.getKeys(false))try{
            String pre="placed."+id+".";
            String mob=y.getString(pre+"mob");
            if(!DROP.containsKey(mob))continue;
            placed.put(id,new Stack(mob,y.getInt(pre+"count"),y.getLong(pre+"stored"),
                y.getLong(pre+"xp"),UUID.fromString(y.getString(pre+"owner"))));
        }catch(Exception ex){plugin.getLogger().warning("Skipped invalid spawner: "+id);}
        ConfigurationSection ps=y.getConfigurationSection("prices");
        if(ps!=null)for(String mob:ps.getKeys(false))prices.put(mob,Math.max(0,ps.getLong(mob)));
    }
    public void save(){
        YamlConfiguration y=new YamlConfiguration();
        placed.forEach((id,s)->{
            String pre="placed."+id+".";
            y.set(pre+"mob",s.mob());y.set(pre+"count",s.count());y.set(pre+"stored",s.stored());
            y.set(pre+"xp",s.xp());y.set(pre+"owner",s.owner().toString());
        });
        prices.forEach((mob,cost)->y.set("prices."+mob,cost));
        try{file.getParentFile().mkdirs();y.save(file);}
        catch(IOException ex){plugin.getLogger().severe("Could not save spawners.yml: "+ex.getMessage());}
    }
}
