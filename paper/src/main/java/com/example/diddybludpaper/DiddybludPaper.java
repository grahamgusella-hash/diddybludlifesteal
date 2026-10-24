package com.example.diddybludpaper;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Paper-only Diddyblud Lifesteal. Vanilla players need no client mod. */
public final class DiddybludPaper extends JavaPlugin implements Listener {
    private final Map<UUID,Long> cash=new HashMap<>(),shards=new HashMap<>();
    private final Map<String,Long> buy=new LinkedHashMap<>(),sell=new LinkedHashMap<>(),shardPrices=new LinkedHashMap<>();
    private final Map<String,String> assignments=new LinkedHashMap<>();
    private final Map<String,Material> categories=new LinkedHashMap<>();
    private final Set<String> offCategories=new HashSet<>(),offItems=new HashSet<>();
    private final Map<UUID,Map<String,Location>> homes=new HashMap<>();
    private final Map<UUID,UUID> requests=new HashMap<>();
    private final Map<String,ItemStack[]> vaults=new HashMap<>();
    private final Map<Integer,Listing> auctions=new LinkedHashMap<>();
    private final Map<Integer,Order> orders=new LinkedHashMap<>();
    private final Map<UUID,Integer> sellTransactions=new HashMap<>();
    // Online playtime in seconds until the next automatic +10 shard reward.
    // Remaining time is saved so logging out or restarting cannot reset the clock.
    private static final long SHARD_INTERVAL_SECONDS=30L*60L;
    private static final long SHARD_REWARD=10L;
    private final Map<UUID,Long> shardSecondsRemaining=new HashMap<>();
    private final Map<UUID,Scoreboard> sidebars=new HashMap<>();
    private int nextAuction=1,nextOrder=1,heartCap=100;
    private UUID queuePlayer=null;
    private NamespacedKey heartKey,spawnerKey,halloweenKey;
    private File dataFile;
    private PaperSpawners spawners;
    // One world-wide spawn location shared by every player and persisted in server-data.yml.
    private Location serverSpawn;
    private record Listing(UUID seller,ItemStack stack,long price){}
    private record Order(UUID buyer,String item,int count,long price){}

    @Override public void onEnable(){
        saveDefaultConfig();
        heartKey=new NamespacedKey(this,"heart");
        spawnerKey=new NamespacedKey(this,"spawner_type");
        halloweenKey=new NamespacedKey(this,"halloween_filler");
        dataFile=new File(getDataFolder(),"server-data.yml");
        defaults();load();
        for(World world:getServer().getWorlds())setRadarOff(world);
        spawners=new PaperSpawners(this,spawnerKey);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getPluginManager().registerEvents(spawners,this);
        getServer().getScheduler().runTaskTimer(this,()->spawners.produce(),100L,100L);
        getServer().getScheduler().runTaskTimer(this,this::save,6000L,6000L);
        // Update the player HUD and online-playtime reward once every second.
        getServer().getScheduler().runTaskTimer(this,this::tickShardRewards,20L,20L);
        for(Player online:getServer().getOnlinePlayers())initializeSidebar(online);
        getLogger().info("Diddyblud Paper enabled with server-only Lifesteal and menus.");
    }
    @Override public void onDisable(){
        // Snapshot vaults that are still open when the server stops. Bukkit may
        // not have fired InventoryCloseEvent for them yet.
        captureOpenVaults();
        // Keep countdowns in shardSecondsRemaining for the last data write.
        // Persist world spawn, all shop categories/prices, accounts and vaults.
        save();
        // Virtual spawner locations, stack counts, stored drops and XP use
        // their separate spawners.yml file.
        if(spawners!=null)spawners.save();
    }
    private void captureOpenVaults(){
        for(Player player:getServer().getOnlinePlayers()){
            Inventory top=player.getOpenInventory().getTopInventory();
            if(!(top.getHolder() instanceof MenuHolder holder))continue;
            if(!holder.kind.startsWith("vault:"))continue;
            String key=player.getUniqueId()+"-"+holder.kind.substring("vault:".length());
            vaults.put(key,cloneItems(top.getContents()));
        }
    }
    private static ItemStack[] cloneItems(ItemStack[] items){
        ItemStack[] copy=new ItemStack[items.length];
        for(int i=0;i<items.length;i++)
            copy[i]=items[i]==null?null:items[i].clone();
        return copy;
    }
    public NamespacedKey spawnerKey(){return spawnerKey;}
    public long funds(UUID id){return cash.getOrDefault(id,0L);}
    public boolean charge(UUID id,long amount){
        if(amount<0||funds(id)<amount)return false;
        cash.put(id,funds(id)-amount);save();return true;
    }
    public boolean credit(UUID id,long amount){
        if(amount<0||funds(id)>Long.MAX_VALUE-amount)return false;
        cash.put(id,funds(id)+amount);save();return true;
    }
    public long price(String id){return buy.getOrDefault(id,0L);}
    public long sellPrice(String id){return sell.getOrDefault(id,0L);}
    private void defaults(){
        for(String spec:new String[]{"combat:TOTEM_OF_UNDYING","enchantments:ENCHANTED_BOOK","shards:DIAMOND",
            "drops:BLAZE_ROD","blocks:OAK_LOG","farming:CARROT","storage:CHEST",
            "redstone:REDSTONE","spawners:SPAWNER"}) {
            String[] p=spec.split(":");categories.put(p[0],Material.valueOf(p[1]));
        }
        // category, material, buy, sell; all values are per item.
        String[] rows={
            "combat,TOTEM_OF_UNDYING,30000,6000","combat,ENCHANTED_GOLDEN_APPLE,50000,8500",
            "combat,GOLDEN_APPLE,1800,350","combat,END_CRYSTAL,5500,1000","combat,OBSIDIAN,250,38",
            "combat,CRYING_OBSIDIAN,850,120","combat,SHIELD,450,90",
            "combat,DIAMOND_SWORD,8000,1200","combat,MACE,70000,12000",
            "combat,DIAMOND_HELMET,11000,1800","combat,DIAMOND_CHESTPLATE,18000,2900",
            "combat,DIAMOND_LEGGINGS,15500,2500","combat,DIAMOND_BOOTS,10000,1600",
            "combat,NETHERITE_HELMET,58000,9500","combat,NETHERITE_CHESTPLATE,80000,13000",
            "combat,NETHERITE_LEGGINGS,70000,11500","combat,NETHERITE_BOOTS,50000,8200",
            "combat,BOW,700,110","combat,ARROW,45,8","combat,HEART,75000,15000",
            "drops,BLAZE_ROD,450,18","drops,BONE,75,6","drops,ROTTEN_FLESH,35,4",
            "drops,GUNPOWDER,300,12","drops,STRING,100,7","drops,ENDER_PEARL,950,14",
            "drops,SLIME_BALL,340,55","drops,SPIDER_EYE,80,12","drops,LEATHER,125,24",
            "blocks,OAK_LOG,130,20","blocks,SPRUCE_LOG,130,20","blocks,BIRCH_LOG,130,20",
            "blocks,DARK_OAK_LOG,150,22","blocks,JUNGLE_LOG,150,22","blocks,MANGROVE_LOG,170,25",
            "blocks,OAK_PLANKS,40,6","blocks,STONE,65,10","blocks,COBBLESTONE,40,6","blocks,DIRT,30,3",
            "farming,CARROT,65,8","farming,POTATO,65,8","farming,WHEAT,85,12",
            "farming,WHEAT_SEEDS,25,3","farming,SUGAR_CANE,90,12","farming,PUMPKIN,160,25",
            "farming,BREAD,160,25","farming,COOKED_BEEF,250,30","farming,GOLDEN_CARROT,600,100",
            "storage,CHEST,400,65","storage,BARREL,420,65","storage,ENDER_CHEST,8000,1300",
            "storage,SHULKER_BOX,35000,5500","storage,HOPPER,1800,250",
            "storage,CRAFTING_TABLE,250,35","storage,FURNACE,380,60",
            "storage,DIAMOND,4500,600","storage,NETHERITE_INGOT,35000,5000",
            "redstone,REDSTONE,120,20","redstone,REPEATER,620,95",
            "redstone,COMPARATOR,850,120","redstone,OBSERVER,920,140",
            "redstone,PISTON,700,105","redstone,STICKY_PISTON,1100,170",
            "redstone,REDSTONE_BLOCK,1080,170","redstone,LEVER,140,20",
            "redstone,DISPENSER,900,135","redstone,DROPPER,680,100",
            "enchantments,BOOK,140,28","enchantments,BOOKSHELF,550,100",
            "enchantments,LAPIS_LAZULI,80,16","enchantments,EXPERIENCE_BOTTLE,500,75",
            "enchantments,ENCHANTING_TABLE,12000,2200","enchantments,ANVIL,2800,500"
        };
        for(String row:rows){
            String[] x=row.split(",");
            if(!x[1].equals("HEART") && Material.matchMaterial(x[1])==null)continue;
            buy.putIfAbsent(x[1],Long.parseLong(x[2]));
            sell.putIfAbsent(x[1],Long.parseLong(x[3]));
            assignments.putIfAbsent(x[1],x[0]);
        }
        String ench="protection:4 fire_protection:4 feather_falling:4 blast_protection:4 projectile_protection:4 respiration:3 aqua_affinity:1 thorns:3 depth_strider:3 frost_walker:2 binding_curse:1 soul_speed:3 swift_sneak:3 sharpness:5 smite:5 bane_of_arthropods:5 knockback:2 fire_aspect:2 looting:3 sweeping_edge:3 efficiency:5 silk_touch:1 unbreaking:3 fortune:3 power:5 punch:2 flame:1 infinity:1 luck_of_the_sea:3 lure:3 loyalty:3 impaling:5 riptide:3 channeling:1 multishot:1 quick_charge:3 piercing:4 density:5 breach:4 wind_burst:3 lunge:3 mending:1 vanishing_curse:1";
        for(String entry:ench.split(" ")){
            String id="ENCHANT:"+entry.split(":")[0];
            buy.putIfAbsent(id,entry.startsWith("mending")?65000L:18000L);
            sell.putIfAbsent(id,entry.startsWith("mending")?8500L:2300L);
            assignments.putIfAbsent(id,"enchantments");
        }
        shardPrices.putIfAbsent("DIAMOND",50L);
        shardPrices.putIfAbsent("NETHERITE_INGOT",250L);
        shardPrices.putIfAbsent("HEART",500L);
    }
    private YamlConfiguration yaml(){return YamlConfiguration.loadConfiguration(dataFile);}
    private void load(){
        if(!dataFile.exists())return;
        YamlConfiguration y=yaml();
        heartCap=Math.max(1,Math.min(500,y.getInt("heart-cap",100)));
        ConfigurationSection timers=y.getConfigurationSection("shard-reward-seconds");
        if(timers!=null)for(String id:timers.getKeys(false)){
            try {
                UUID uuid=UUID.fromString(id);
                long remaining=timers.getLong(id,SHARD_INTERVAL_SECONDS);
                shardSecondsRemaining.put(uuid,Math.max(0L,Math.min(SHARD_INTERVAL_SECONDS,remaining)));
            } catch(IllegalArgumentException ignored){}
        }
        Location loadedSpawn=y.getLocation("server-spawn");
        if(loadedSpawn!=null)serverSpawn=loadedSpawn.clone();
        for(String section:new String[]{"cash","shards"}){
            ConfigurationSection s=y.getConfigurationSection(section);
            if(s!=null)for(String k:s.getKeys(false)){
                try{(section.equals("cash")?cash:shards).put(UUID.fromString(k),Math.max(0,s.getLong(k)));}
                catch(Exception ignored){}
            }
        }
        for(String section:new String[]{"buy","sell","shard-prices"}){
            ConfigurationSection s=y.getConfigurationSection(section);
            if(s==null)continue;
            Map<String,Long> target=section.equals("buy")?buy:section.equals("sell")?sell:shardPrices;
            for(String k:s.getKeys(false))target.put(k,Math.max(0,s.getLong(k)));
        }
        ConfigurationSection cs=y.getConfigurationSection("categories");
        if(cs!=null)for(String k:cs.getKeys(false)){
            Material m=Material.matchMaterial(cs.getString(k,""));
            if(m!=null)categories.put(k,m);
        }
        ConfigurationSection as=y.getConfigurationSection("assignment");
        if(as!=null)for(String k:as.getKeys(false))assignments.put(k,as.getString(k));
        offCategories.addAll(y.getStringList("off-categories"));
        offItems.addAll(y.getStringList("off-items"));
        ConfigurationSection hs=y.getConfigurationSection("homes");
        if(hs!=null)for(String playerId:hs.getKeys(false)){
            UUID playerIdParsed;
            try{playerIdParsed=UUID.fromString(playerId);}
            catch(IllegalArgumentException ignored){continue;}
            Object oldValue=hs.get(playerId);
            if(oldValue instanceof Location oldHome){
                homes.computeIfAbsent(playerIdParsed,id->new LinkedHashMap<>())
                    .put("home",oldHome.clone());
                continue;
            }
            ConfigurationSection personal=hs.getConfigurationSection(playerId);
            if(personal==null)continue;
            Map<String,Location> named=homes.computeIfAbsent(playerIdParsed,id->new LinkedHashMap<>());
            for(String name:personal.getKeys(false)){
                Location location=personal.getLocation(name);
                if(location!=null)named.put(name.toLowerCase(Locale.ROOT),location.clone());
            }
        }
        ConfigurationSection vs=y.getConfigurationSection("vaults");
        if(vs!=null)for(String k:vs.getKeys(false)){
            List<?> list=vs.getList(k);
            if(list!=null){ItemStack[] stacks=new ItemStack[27];
                for(int i=0;i<Math.min(27,list.size());i++)if(list.get(i) instanceof ItemStack item)stacks[i]=item;
                vaults.put(k,stacks);
            }
        }
        ConfigurationSection au=y.getConfigurationSection("auctions");
        if(au!=null)for(String k:au.getKeys(false))try{
            int id=Integer.parseInt(k);String p="auctions."+k+".";
            ItemStack item=y.getItemStack(p+"stack");
            if(item!=null){auctions.put(id,new Listing(UUID.fromString(y.getString(p+"seller")),item,y.getLong(p+"price")));nextAuction=Math.max(nextAuction,id+1);}
        }catch(Exception ignored){}
        ConfigurationSection os=y.getConfigurationSection("orders");
        if(os!=null)for(String k:os.getKeys(false))try{
            int id=Integer.parseInt(k);String p="orders."+k+".";
            orders.put(id,new Order(UUID.fromString(y.getString(p+"buyer")),y.getString(p+"item"),y.getInt(p+"count"),y.getLong(p+"price")));
            nextOrder=Math.max(nextOrder,id+1);
        }catch(Exception ignored){}
    }
    public void save(){
        YamlConfiguration y=new YamlConfiguration();
        y.set("heart-cap",heartCap);
        y.set("server-spawn",serverSpawn);
        cash.forEach((id,v)->y.set("cash."+id,v));
        shards.forEach((id,v)->y.set("shards."+id,v));
        shardSecondsRemaining.forEach((id,v)->y.set("shard-reward-seconds."+id,v));
        buy.forEach((id,v)->y.set("buy."+id,v));
        sell.forEach((id,v)->y.set("sell."+id,v));
        shardPrices.forEach((id,v)->y.set("shard-prices."+id,v));
        categories.forEach((id,v)->y.set("categories."+id,v.name()));
        assignments.forEach((id,v)->y.set("assignment."+id,v));
        y.set("off-categories",new ArrayList<>(offCategories));
        y.set("off-items",new ArrayList<>(offItems));
        for(var playerHomes:homes.entrySet()) for(var namedHome:playerHomes.getValue().entrySet())
            y.set("homes."+playerHomes.getKey()+"."+namedHome.getKey(),namedHome.getValue());
        // Use a snapshot so inventory edits cannot mutate items while YAML is written.
        vaults.forEach((id,v)->y.set("vaults."+id,Arrays.asList(cloneItems(v))));
        auctions.forEach((id,v)->{
            String p="auctions."+id+".";
            y.set(p+"seller",v.seller().toString());y.set(p+"stack",v.stack());y.set(p+"price",v.price());
        });
        orders.forEach((id,v)->{
            String p="orders."+id+".";
            y.set(p+"buyer",v.buyer().toString());y.set(p+"item",v.item());
            y.set(p+"count",v.count());y.set(p+"price",v.price());
        });
        try{
            getDataFolder().mkdirs();
            File temporary=new File(getDataFolder(),"server-data.yml.tmp");
            y.save(temporary);
            try{
                java.nio.file.Files.move(temporary.toPath(),dataFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }catch(java.nio.file.AtomicMoveNotSupportedException ex){
                java.nio.file.Files.move(temporary.toPath(),dataFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }catch(IOException ex){
            getLogger().severe("Failed to save server-data.yml: "+ex.getMessage());
        }
    }
    public static long parseAmount(String input){
        if(!input.matches("(?i)\\d+(?:\\.\\d+)?[kmbt]?"))throw new IllegalArgumentException("Use an amount like 1k, 1m, 1b or 1t.");
        String x=input.toLowerCase(Locale.ROOT);char end=x.charAt(x.length()-1);
        long mult=switch(end){case 'k'->1000L;case 'm'->1000000L;case 'b'->1000000000L;case 't'->1000000000000L;default->1L;};
        try{
            long v=new BigDecimal(mult==1?x:x.substring(0,x.length()-1)).multiply(BigDecimal.valueOf(mult)).longValueExact();
            if(v<=0)throw new IllegalArgumentException("Amount must be positive.");
            return v;
        }catch(ArithmeticException ex){throw new IllegalArgumentException("Invalid amount/overflow.");}
    }
    private void setRadarOff(World world){
        // Minecraft 26.3 locator bar is not the right-hand economy scoreboard.
        // Disable this game rule in every dimension and newly loaded world.
        try{
            world.setGameRule(GameRule.LOCATOR_BAR,Boolean.FALSE);
        }catch(Exception ex){
            getLogger().warning("Unable to disable locator bar: "+ex.getMessage());
        }
    }
    @EventHandler public void whenWorldLoads(org.bukkit.event.world.WorldLoadEvent event){
        setRadarOff(event.getWorld());
    }
    private static String validHomeName(String raw){
        String name=raw.toLowerCase(Locale.ROOT);
        if(!name.matches("[a-z0-9_-]{1,24}"))
            throw new IllegalArgumentException("Home name: 1-24 letters, digits, _ or -.");
        return name;
    }
    private static long parseSetAmount(String input){
        // Setting a balance to zero must be permitted; giving/paying zero stays invalid.
        if("0".equals(input))return 0L;
        return parseAmount(input);
    }
    private static ItemStack icon(Material type,String name){
        ItemStack s=new ItemStack(type);
        ItemMeta m=s.getItemMeta();
        if(m!=null){m.setDisplayName(name);s.setItemMeta(m);}return s;
    }
    private String id(ItemStack stack){
        if(stack==null||stack.getType().isAir())return null;
        if(isHeart(stack))return "HEART";
        if(spawners.mobFromItem(stack)!=null)return null; // never sell unique spawner as a vanilla item
        if(stack.getType()==Material.ENCHANTED_BOOK){
            var meta=(org.bukkit.inventory.meta.EnchantmentStorageMeta)stack.getItemMeta();
            if(meta!=null&&meta.getStoredEnchants().size()==1){
                var ench=meta.getStoredEnchants().keySet().iterator().next();
                String key="ENCHANT:"+ench.getKey().getKey();
                if(buy.containsKey(key))return key;
            }
            return null;
        }
        return stack.getType().name();
    }
    public ItemStack heart(int n){
        ItemStack s=icon(Material.NETHER_STAR,"§c❤ Heart");
        s.setAmount(Math.max(1,Math.min(n,64)));
        ItemMeta m=s.getItemMeta();
        m.getPersistentDataContainer().set(heartKey,PersistentDataType.BYTE,(byte)1);s.setItemMeta(m);
        return s;
    }
    public boolean isHeart(ItemStack stack){
        return stack!=null&&stack.getType()==Material.NETHER_STAR&&stack.hasItemMeta()&&
            stack.getItemMeta().getPersistentDataContainer().has(heartKey,PersistentDataType.BYTE);
    }
    private int maxHearts(Player p){
        var a=p.getAttribute(Attribute.MAX_HEALTH);
        return a==null?20:(int)Math.round(a.getBaseValue()/2);
    }
    private void setHearts(Player p,int value){
        var a=p.getAttribute(Attribute.MAX_HEALTH);
        if(a==null)return;
        a.setBaseValue(Math.max(2,value*2));
        p.setHealth(Math.min(p.getHealth(),a.getValue()));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onHeartUse(PlayerInteractEvent event){
        org.bukkit.event.block.Action action=event.getAction();
        if(action!=org.bukkit.event.block.Action.RIGHT_CLICK_AIR&&
           action!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;
        EquipmentSlot hand=event.getHand();
        if(hand!=EquipmentSlot.HAND&&hand!=EquipmentSlot.OFF_HAND)return;
        Player player=event.getPlayer();
        ItemStack stack=hand==EquipmentSlot.HAND
            ?player.getInventory().getItemInMainHand()
            :player.getInventory().getItemInOffHand();
        if(!isHeart(stack))return;

        // Redeem before Minecraft treats the Nether Star as an ordinary item or
        // performs the right-clicked block interaction; works in either hand.
        event.setCancelled(true);
        int current=maxHearts(player);
        if(current>=heartCap){
            player.sendMessage("§cYou have reached the "+heartCap+"-heart maximum.");
            return;
        }
        if(player.getAttribute(Attribute.MAX_HEALTH)==null){
            player.sendMessage("§cCannot redeem: your maximum-health attribute is unavailable.");
            return;
        }
        try{
            setHearts(player,current+1);
        }catch(IllegalArgumentException ex){
            getLogger().warning("Cannot apply heart to "+player.getName()+": "+ex.getMessage());
            player.sendMessage("§cCould not apply this Heart. Ask an OP to check the console.");
            return;
        }
        if(player.getGameMode()!=GameMode.CREATIVE){
            int remaining=stack.getAmount()-1;
            ItemStack replacement=remaining<=0?new ItemStack(Material.AIR):stack.clone();
            if(remaining>0)replacement.setAmount(remaining);
            if(hand==EquipmentSlot.HAND)player.getInventory().setItemInMainHand(replacement);
            else player.getInventory().setItemInOffHand(replacement);
        }
        // Let the newly gained heart be usable immediately.
        var attribute=player.getAttribute(Attribute.MAX_HEALTH);
        if(attribute!=null)player.setHealth(Math.min(attribute.getValue(),player.getHealth()+2.0));
        player.sendMessage("§a❤ Heart redeemed! Maximum hearts: "+(current+1)+"/"+heartCap);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e){
        Player victim=e.getEntity();
        Player killer=victim.getKiller();
        int heartsBeforeDeath=maxHearts(victim);

        // A death costs one maximum heart, whether caused by PvP or something else.
        // Bukkit cannot assign zero maximum health, so a player whose final heart
        // is lost is banned rather than left at zero health.
        if(heartsBeforeDeath<=1){
            // Keep the last valid max-health value until the ban has been issued.
            // Vanilla's ban command writes to banned-players.json persistently.
            String victimName=victim.getName();
            getServer().getScheduler().runTask(this,()->{
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "ban "+victimName+" You ran out of Lifesteal hearts");
                if(victim.isOnline())victim.kickPlayer("§cYou ran out of Lifesteal hearts.");
            });
        }else{
            setHearts(victim,heartsBeforeDeath-1);
        }

        // Only player kills award a redeemable Heart item. The killer does not
        // automatically gain max health: somebody must collect and redeem it.
        if(killer!=null&&!killer.equals(victim)){
            e.getDrops().add(heart(1));
        }
    }
    private Inventory menu(String kind,int page,UUID owner){
        MenuHolder h=new MenuHolder(kind,page,owner);
        String title=kind.startsWith("vault:") ? "§6§lHaunted Vault §8#"+kind.substring(6)
            : (kind.length()>28?kind.substring(0,28):kind);
        Inventory inv=Bukkit.createInventory(h,27,title);
        h.inv=inv;return inv;
    }
    private static final class MenuHolder implements InventoryHolder {
        final String kind;final int page;final UUID owner;Inventory inv;
        MenuHolder(String kind,int page,UUID owner){this.kind=kind;this.page=page;this.owner=owner;}
        public Inventory getInventory(){return inv;}
    }
    private static final String[] FIXED_CATEGORIES={
        "spawners","combat","enchantments","shards",
        "drops","blocks","farming","storage","redstone"
    };
    private static final int[] FIXED_SLOTS={11,12,13,14,20,21,22,23,24};
    private List<String> visibleCategories(int page){
        if(page==0)return Arrays.asList(FIXED_CATEGORIES);
        List<String> extras=new ArrayList<>();
        for(String category:categories.keySet()){
            if(!Arrays.asList(FIXED_CATEGORIES).contains(category))extras.add(category);
        }
        int begin=(page-1)*18;
        if(begin>=extras.size())return Collections.emptyList();
        return extras.subList(begin,Math.min(begin+18,extras.size()));
    }
    /**
     * Decorations only occupy unassigned UI slots. Vault slots are reserved
     * for player items, so their 27 storage slots are never decorated.
     */
    private void decorateHalloween(Inventory inv,String kind){
        if(kind.startsWith("vault:"))return;
        int[] spaces;
        if(kind.equals("shop")||kind.equals("shopadmin")){
            spaces=new int[]{0,1,2,6,7,8,9,10,15,16,17,20,21,22,23,24};
        }else if(kind.equals("sell")){
            spaces=new int[]{18,19,20,21,23,24,25};
        }else if(kind.startsWith("cat:")||kind.startsWith("catadmin:")){
            spaces=new int[]{20,21,23,24};
        }else if(kind.equals("customitems")){
            spaces=new int[]{0,1,2,3,4,5,6,7,8,9,20,21,22,23,24,25};
        }else if(kind.equals("rtp")){
            spaces=new int[]{0,1,2,3,4,5,6,7,8,9,10,12,13,14,16,17,18,19,20,21,23,24,25,26};
        }else{
            spaces=new int[]{19,20,21,22,23,24};
        }
        for(int slot:spaces){
            if(slot<0||slot>=inv.getSize()||inv.getItem(slot)!=null)continue;
            Material type;
            String label;
            if(slot==0||slot==8||slot==18||slot==26){
                type=Material.JACK_O_LANTERN;label="§6Halloween Pumpkin";
            }else if(slot==4||slot==13||slot==22){
                type=Material.COBWEB;label="§7Haunted Web";
            }else if(slot%2==0){
                type=Material.ORANGE_STAINED_GLASS_PANE;label="§6Halloween Border";
            }else{
                type=Material.BLACK_STAINED_GLASS_PANE;label="§8Halloween Border";
            }
            ItemStack decoration=icon(type,label);
            ItemMeta decorationMeta=decoration.getItemMeta();
            decorationMeta.getPersistentDataContainer().set(halloweenKey,PersistentDataType.BYTE,(byte)1);
            decoration.setItemMeta(decorationMeta);
            inv.setItem(slot,decoration);
        }
    }
    private void openShop(Player p,int page,boolean admin){
        Inventory inv=menu(admin?"shopadmin":"shop",page,p.getUniqueId());
        List<String> shown=visibleCategories(page);
        for(int i=0;i<shown.size();i++){
            String category=shown.get(i);
            Material material=categories.get(category);
            if(material==null)continue;
            int slot=page==0?FIXED_SLOTS[i]:i;
            inv.setItem(slot,icon(material,(offCategories.contains(category)?"§cOFF §r":"")+category));
        }
        inv.setItem(18,icon(Material.ARROW,"Previous page"));
        inv.setItem(25,icon(Material.ARROW,"Next page"));
        if(p.hasPermission("diddyblud.admin")){
            inv.setItem(26,icon(Material.COMPARATOR,admin?"Exit editor":"Admin settings"));
            if(admin)inv.setItem(19,icon(Material.ANVIL,"Hold renamed item: add category"));
        }
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void openCategory(Player p,String category,int page,boolean admin){
        Inventory inv=menu((admin?"catadmin:":"cat:")+category,page,p.getUniqueId());
        List<String> ids=assignments.keySet().stream().filter(id->category.equals(assignments.get(id))).toList();
        int start=page*18;
        for(int i=0;i<18&&start+i<ids.size();i++){
            String id=ids.get(start+i);
            if(!buy.containsKey(id))continue;
            ItemStack s=shopItem(id);
            if(s==null)continue;
            ItemMeta meta=s.getItemMeta();meta.setDisplayName((offItems.contains(id)?"§cDISABLED ":"")+id+
               " §aBuy $"+buy.get(id)+" §eSell $"+sell.getOrDefault(id,0L));s.setItemMeta(meta);inv.setItem(i,s);
        }
        inv.setItem(18,icon(Material.ARROW,"Back"));
        inv.setItem(19,icon(Material.PAPER,"Previous"));
        inv.setItem(25,icon(Material.PAPER,"Next"));
        if(admin)inv.setItem(22,icon(Material.ANVIL,"Add held item to category"));
        inv.setItem(26,icon(Material.EMERALD,"Balance $"+funds(p.getUniqueId())));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private ItemStack shopItem(String id){
        if(id.equals("HEART"))return heart(1);
        if(id.startsWith("ENCHANT:")){
            org.bukkit.enchantments.Enchantment ench=Registry.ENCHANTMENT.get(NamespacedKey.minecraft(id.substring(8)));
            if(ench==null)return null;
            ItemStack stack=new ItemStack(Material.ENCHANTED_BOOK);
            org.bukkit.inventory.meta.EnchantmentStorageMeta meta=(org.bukkit.inventory.meta.EnchantmentStorageMeta)stack.getItemMeta();
            meta.addStoredEnchant(ench,ench.getMaxLevel(),true);
            stack.setItemMeta(meta);return stack;
        }
        Material m=Material.matchMaterial(id);
        return m==null?null:new ItemStack(m);
    }
    private void openSell(Player p){
        Inventory inv=menu("sell",0,p.getUniqueId());
        inv.setItem(22,icon(Material.EMERALD,"CONFIRM sale"));
        inv.setItem(26,icon(Material.BARRIER,"CANCEL — return items"));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void openRtp(Player p){
        Inventory inv=menu("rtp",0,p.getUniqueId());
        inv.setItem(11,icon(Material.GRASS_BLOCK,"Random teleport: near (up to 2000 blocks)"));
        inv.setItem(15,icon(Material.ENDER_PEARL,"Random teleport: far (2000–8000 blocks)"));
        inv.setItem(22,icon(Material.BARRIER,"Cancel"));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void teleportRandom(Player p,boolean far){
        World world=p.getWorld();Random rng=new Random();
        for(int tries=0;tries<30;tries++){
            int distance=far?2000+rng.nextInt(6001):200+rng.nextInt(1801);
            double angle=rng.nextDouble()*Math.PI*2;
            int x=p.getLocation().getBlockX()+(int)(Math.cos(angle)*distance);
            int z=p.getLocation().getBlockZ()+(int)(Math.sin(angle)*distance);
            int y=world.getHighestBlockYAt(x,z);
            Material below=world.getBlockAt(x,y-1,z).getType();
            if(!below.isSolid()||below==Material.LAVA)continue;
            Location at=new Location(world,x+0.5,y+1,z+0.5);
            if(at.getBlock().isEmpty()&&at.clone().add(0,1,0).getBlock().isEmpty()){
                p.teleport(at);p.sendMessage("§aRandom teleport successful.");return;
            }
        }
        p.sendMessage("§cNo safe random destination found.");
    }
    private void openCustom(Player p){
        Inventory inv=menu("customitems",0,p.getUniqueId());
        inv.setItem(10,heart(1));String[] mobs=PaperSpawners.MOBS;
        for(int i=0;i<mobs.length;i++)inv.setItem(11+i,spawners.spawner(mobs[i],1));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void openVault(Player p,int number){
        String key=p.getUniqueId()+"-"+number;
        Inventory inv=menu("vault:"+number,0,p.getUniqueId());
        ItemStack[] stored=vaults.get(key);
        if(stored!=null)inv.setContents(stored);
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void openList(Player p,String type,int page){
        Inventory inv=menu(type,page,p.getUniqueId());
        if(type.equals("spawners")){
            for(int i=0;i<PaperSpawners.MOBS.length;i++){
                String mob=PaperSpawners.MOBS[i];long price=spawners.price(mob);
                ItemStack s=spawners.spawner(mob,1);
                ItemMeta meta=s.getItemMeta();meta.setDisplayName(mob+" Spawner | $"+price);s.setItemMeta(meta);
                inv.setItem(i,s);
            }
        }else if(type.equals("shards")){
            List<String> ids=new ArrayList<>(shardPrices.keySet());
            for(int i=0;i<18&&page*18+i<ids.size();i++){
                String id=ids.get(page*18+i);ItemStack s=shopItem(id);if(s==null)continue;
                ItemMeta m=s.getItemMeta();m.setDisplayName(id+" | "+shardPrices.get(id)+" shards");s.setItemMeta(m);inv.setItem(i,s);
            }
        }else if(type.equals("auctions")){
            List<Map.Entry<Integer,Listing>> all=new ArrayList<>(auctions.entrySet());
            for(int i=0;i<18&&page*18+i<all.size();i++){
                var row=all.get(page*18+i);ItemStack stack=row.getValue().stack().clone();
                ItemMeta m=stack.getItemMeta();m.setDisplayName("Auction #"+row.getKey()+" $"+row.getValue().price());stack.setItemMeta(m);inv.setItem(i,stack);
            }
        }else if(type.equals("orders")){
            List<Map.Entry<Integer,Order>> all=new ArrayList<>(orders.entrySet());
            for(int i=0;i<18&&page*18+i<all.size();i++){
                var row=all.get(page*18+i);ItemStack s=shopItem(row.getValue().item());if(s==null)continue;
                ItemMeta m=s.getItemMeta();m.setDisplayName("Order #"+row.getKey()+" "+row.getValue().count()+" for $"+row.getValue().price());s.setItemMeta(m);inv.setItem(i,s);
            }
        }
        inv.setItem(18,icon(Material.ARROW,"Back / previous"));
        inv.setItem(25,icon(Material.ARROW,"Next"));
        inv.setItem(26,icon(Material.BARRIER,"Close"));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void give(Player p,ItemStack item){HashMap<Integer,ItemStack> extra=p.getInventory().addItem(item);
        extra.values().forEach(s->p.getWorld().dropItemNaturally(p.getLocation(),s));}
    @EventHandler public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(!(e.getView().getTopInventory().getHolder() instanceof MenuHolder h))return;
        if(!h.owner.equals(p.getUniqueId())){e.setCancelled(true);return;}
        int slot=e.getRawSlot();String type=h.kind;
        if(type.startsWith("vault:"))return;
        if(type.equals("sell")){
            if(slot>=18&&slot<27){e.setCancelled(true);
                if(slot==26)p.closeInventory();
                else if(slot==22)confirmSell(p,e.getView().getTopInventory());
            }else if(e.isShiftClick())e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if(slot<0||slot>=27)return;
        ItemStack clicked=e.getView().getTopInventory().getItem(slot);
        if(clicked!=null&&clicked.hasItemMeta()&&
            clicked.getItemMeta().getPersistentDataContainer().has(halloweenKey,PersistentDataType.BYTE))
            return; // Halloween filler is decorative, never a shop product or button.
        if(type.equals("shop")||type.equals("shopadmin")){
            boolean admin=type.equals("shopadmin")&&p.hasPermission("diddyblud.admin");
            if(slot==26&&p.hasPermission("diddyblud.admin")){openShop(p,0,!admin);return;}
            if(slot==19&&admin){
                ItemStack held=p.getInventory().getItemInMainHand();
                if(held.getType().isAir()||!held.hasItemMeta()||!held.getItemMeta().hasDisplayName()){
                    p.sendMessage("§cRename the category icon item in an anvil first.");return;
                }
                String name=ChatColor.stripColor(held.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT).replace(' ','_');
                categories.putIfAbsent(name,held.getType());save();openShop(p,h.page,true);return;
            }
            if(slot==18){openShop(p,Math.max(0,h.page-1),admin);return;}
            if(slot==25){openShop(p,h.page+1,admin);return;}
            List<String> cats=visibleCategories(h.page);
            int at=-1;
            if(h.page==0){
                for(int i=0;i<FIXED_SLOTS.length;i++)if(FIXED_SLOTS[i]==slot){at=i;break;}
            }else if(slot<18)at=slot;
            if(at<0||at>=cats.size())return;
            String cat=cats.get(at);
            if(admin&&e.isShiftClick()){
                if(!offCategories.add(cat))offCategories.remove(cat);save();openShop(p,h.page,true);return;
            }
            if(offCategories.contains(cat)&&!admin){p.sendMessage("§cCategory disabled.");return;}
            if(cat.equals("shards")||cat.equals("spawners"))openList(p,cat,0);
            else openCategory(p,cat,0,admin);
            return;
        }
        if(type.startsWith("cat:")||type.startsWith("catadmin:")){
            boolean admin=type.startsWith("catadmin:")&&p.hasPermission("diddyblud.admin");
            String category=type.substring(type.indexOf(':')+1);
            if(slot==18){openShop(p,0,admin);return;}
            if(slot==19){openCategory(p,category,Math.max(0,h.page-1),admin);return;}
            if(slot==25){openCategory(p,category,h.page+1,admin);return;}
            if(slot==22&&admin){
                ItemStack held=p.getInventory().getItemInMainHand();
                String id=id(held);if(id==null){p.sendMessage("§cHold an eligible item.");return;}
                buy.putIfAbsent(id,100L);sell.putIfAbsent(id,10L);assignments.put(id,category);
                save();openCategory(p,category,h.page,true);return;
            }
            if(slot>=18)return;
            List<String> ids=assignments.keySet().stream().filter(k->category.equals(assignments.get(k))).toList();
            int at=h.page*18+slot;if(at>=ids.size())return;
            String id=ids.get(at);
            if(admin){
                if(e.isShiftClick()){if(!offItems.add(id))offItems.remove(id);}
                else if(e.isLeftClick())buy.put(id,buy.getOrDefault(id,0L)+100L);
                else if(e.isRightClick())sell.put(id,sell.getOrDefault(id,0L)+10L);
                save();openCategory(p,category,h.page,true);return;
            }
            if(offItems.contains(id)){p.sendMessage("§cItem disabled.");return;}
            if(e.isLeftClick()){
                if(!charge(p.getUniqueId(),buy.getOrDefault(id,0L))){p.sendMessage("§cNot enough money.");return;}
                ItemStack s=shopItem(id);
                if(s==null){credit(p.getUniqueId(),buy.get(id));return;}
                give(p,s);
            }else if(e.isRightClick()){
                for(ItemStack item:p.getInventory().getContents()){
                    if(id.equals(id(item))){
                        if(!credit(p.getUniqueId(),sell.getOrDefault(id,0L)))return;
                        item.setAmount(item.getAmount()-1);return;
                    }
                }
                p.sendMessage("§cYou don't have this item.");
            }
            return;
        }
        if(slot==26){p.closeInventory();return;}
        if(slot==18){if(h.page==0)openShop(p,0,false);else openList(p,type,h.page-1);return;}
        if(slot==25){openList(p,type,h.page+1);return;}
        if(type.equals("rtp")){
            if(slot==11||slot==15){p.closeInventory();teleportRandom(p,slot==15);}
            else if(slot==22)p.closeInventory();
        }else if(type.equals("customitems")){
            if(!p.hasPermission("diddyblud.admin"))return;
            if(slot==10)give(p,heart(1));
            else if(slot>=11&&slot<11+PaperSpawners.MOBS.length)give(p,spawners.spawner(PaperSpawners.MOBS[slot-11],1));
        }else if(type.equals("spawners")){
            if(slot>=PaperSpawners.MOBS.length)return;
            String mob=PaperSpawners.MOBS[slot];
            if(e.isShiftClick()&&p.hasPermission("diddyblud.admin")){
                spawners.adjustPrice(mob,e.isLeftClick()?1000L:-1000L);openList(p,type,0);return;
            }
            long price=spawners.price(mob);
            if(!charge(p.getUniqueId(),price)){p.sendMessage("§cNot enough money.");return;}
            give(p,spawners.spawner(mob,1));
        }else if(type.equals("shards")){
            List<String> ids=new ArrayList<>(shardPrices.keySet());int at=h.page*18+slot;
            if(slot>=18||at>=ids.size())return;
            String id=ids.get(at);
            if(e.isShiftClick()&&p.hasPermission("diddyblud.admin")){
                shardPrices.put(id,Math.max(0,shardPrices.get(id)+(e.isLeftClick()?10:-10)));save();openList(p,type,h.page);return;
            }
            long cost=shardPrices.get(id),bal=shards.getOrDefault(p.getUniqueId(),0L);
            if(cost>bal){p.sendMessage("§cNot enough shards.");return;}
            ItemStack item=shopItem(id);if(item==null)return;
            shards.put(p.getUniqueId(),bal-cost);give(p,item);save();
        }else if(type.equals("auctions")){
            List<Map.Entry<Integer,Listing>> rows=new ArrayList<>(auctions.entrySet());int at=h.page*18+slot;
            if(slot>=18||at>=rows.size())return;
            int key=rows.get(at).getKey();Listing l=auctions.get(key);if(l==null)return;
            if(l.seller().equals(p.getUniqueId())){auctions.remove(key);give(p,l.stack().clone());}
            else {
                if(!charge(p.getUniqueId(),l.price())){p.sendMessage("§cNot enough money.");return;}
                if(!credit(l.seller(),l.price())){credit(p.getUniqueId(),l.price());return;}
                auctions.remove(key);give(p,l.stack().clone());
            }
            save();openList(p,type,h.page);
        }else if(type.equals("orders")){
            List<Map.Entry<Integer,Order>> rows=new ArrayList<>(orders.entrySet());int at=h.page*18+slot;
            if(slot>=18||at>=rows.size())return;
            int key=rows.get(at).getKey();Order o=orders.get(key);if(o==null)return;
            if(o.buyer().equals(p.getUniqueId())){
                if(credit(p.getUniqueId(),o.price()))orders.remove(key);
            }else{
                int available=0;for(ItemStack s:p.getInventory().getContents())if(o.item().equals(id(s)))available+=s.getAmount();
                if(available<o.count()){p.sendMessage("§cNot enough items.");return;}
                Player buyer=Bukkit.getPlayer(o.buyer());
                if(buyer==null){p.sendMessage("§cBuyer must be online.");return;}
                if(!credit(p.getUniqueId(),o.price()))return;
                int remaining=o.count();
                for(ItemStack s:p.getInventory().getContents()){
                    if(s!=null&&o.item().equals(id(s))&&remaining>0){
                        int amount=Math.min(remaining,s.getAmount());s.setAmount(s.getAmount()-amount);remaining-=amount;
                    }
                }
                ItemStack item=shopItem(o.item());if(item!=null){item.setAmount(o.count());give(buyer,item);}
                orders.remove(key);
            }
            save();openList(p,type,h.page);
        }
    }
    /**
     * Base payouts for vanilla obtainable goods not explicitly priced by an OP.
     * Specific configured sell prices always override these default categories.
     * Selling is deliberately less lucrative than buying to prevent shop loops.
     */
    private long fallbackSellPrice(ItemStack stack){
        if(stack==null||stack.getType().isAir())return 0;
        Material material=stack.getType();
        String name=material.name();
        if(!material.isItem()||name.startsWith("LEGACY_")||
            name.contains("SPAWN_EGG")||name.contains("COMMAND_BLOCK")||
            name.contains("STRUCTURE_")||name.contains("JIGSAW")||
            name.contains("BARRIER")||name.contains("DEBUG_STICK")||
            name.contains("LIGHT")||name.contains("KNOWLEDGE_BOOK")||
            name.contains("BEDROCK")||name.contains("END_PORTAL")||
            name.contains("SPAWNER")||name.contains("REINFORCED_DEEPSLATE"))
            return 0;
        // Avoid paying for potentially valuable nested inventory contents as one item.
        if(stack.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta state &&
            state.getBlockState() instanceof org.bukkit.block.ShulkerBox shulker &&
            !shulker.getInventory().isEmpty())return 0;

        if(name.contains("NETHERITE")||name.equals("ELYTRA"))return 2500;
        if(name.equals("DIAMOND")||name.startsWith("DIAMOND_"))return 650;
        if(name.contains("EMERALD"))return 250;
        if(name.contains("ANCIENT_DEBRIS"))return 850;
        if(name.contains("TOTEM_OF_UNDYING"))return 5000;
        if(name.contains("ENCHANTED_GOLDEN_APPLE"))return 7000;
        if(name.contains("GOLDEN_APPLE"))return 250;
        if(name.contains("BEACON")||name.contains("NETHER_STAR"))return 2500;
        if(name.contains("SHULKER"))return 1200;
        if(name.contains("GHAST_TEAR")||name.contains("WITHER_SKELETON_SKULL"))return 450;
        if(name.contains("IRON")||name.contains("GOLD"))return 60;
        if(name.contains("LAPIS")||name.contains("REDSTONE")||name.contains("COPPER"))return 15;
        if(name.contains("ENDER_PEARL")||name.contains("BLAZE_ROD")||name.contains("GUNPOWDER"))return 12;
        if(name.contains("OBSIDIAN"))return 35;
        if(name.contains("BOOK")||name.contains("POTION"))return 20;
        if(name.contains("SWORD")||name.contains("PICKAXE")||name.contains("AXE")||
            name.contains("SHOVEL")||name.contains("HOE")||
            name.contains("HELMET")||name.contains("CHESTPLATE")||
            name.contains("LEGGINGS")||name.contains("BOOTS"))return 45;
        if(name.contains("ORE"))return 35;
        if(name.contains("LOG")||name.contains("STEM")||name.contains("HYPHAE"))return 12;
        if(name.contains("PLANKS")||name.contains("STONE")||name.contains("BRICKS"))return 5;
        if(name.contains("FOOD")||name.contains("COOKED")||name.contains("BREAD"))return 10;
        if(name.contains("SEEDS")||name.contains("LEAVES")||name.equals("DIRT")||
            name.contains("SAND")||name.contains("GRAVEL")||name.contains("COBBLESTONE"))return 2;
        return 8; // common nonrestricted obtainable items
    }
    private long sellValueFor(ItemStack item,String identifier){
        if(identifier==null||offItems.contains(identifier))return 0;
        if(sell.containsKey(identifier))return Math.max(0,sell.get(identifier));
        return fallbackSellPrice(item);
    }
    private void confirmSell(Player p,Inventory inv){
        long total=0;List<Integer> sold=new ArrayList<>();
        for(int i=0;i<18;i++){
            ItemStack s=inv.getItem(i);String id=id(s);
            long unit=sellValueFor(s,id);
            if(unit<=0)continue;
            try{total=Math.addExact(total,Math.multiplyExact(unit,s.getAmount()));}
            catch(ArithmeticException ex){p.sendMessage("§cSale too large.");return;}
            sold.add(i);
        }
        if(total==0){p.sendMessage("§cNo eligible items.");return;}
        if(funds(p.getUniqueId())>Long.MAX_VALUE-total){p.sendMessage("§cBalance limit.");return;}
        for(int slot:sold)inv.setItem(slot,null);
        credit(p.getUniqueId(),total);p.sendMessage("§aSold items for $"+total);
        p.closeInventory();
    }
    @EventHandler public void close(InventoryCloseEvent e){
        if(!(e.getPlayer() instanceof Player p))return;
        if(!(e.getInventory().getHolder() instanceof MenuHolder h))return;
        if(h.kind.equals("sell")){
            for(int i=0;i<18;i++){
                ItemStack s=e.getInventory().getItem(i);
                if(s!=null&&!s.getType().isAir()){
                    e.getInventory().setItem(i,null);
                    give(p,s);
                }
            }
        }else if(h.kind.startsWith("vault:")){
            vaults.put(p.getUniqueId()+"-"+h.kind.substring(6),
                cloneItems(e.getInventory().getContents()));
            save();
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event){
        // The configured server spawn overrides beds, anchors and default world spawn
        // for all deaths, including deaths in other dimensions.
        if(serverSpawn==null||serverSpawn.getWorld()==null)return;
        event.setRespawnLocation(serverSpawn.clone());
    }
    // Uses vanilla scoreboard packets: no client mod or texture pack needed.
    private void initializeSidebar(Player player){
        UUID uuid=player.getUniqueId();
        shardSecondsRemaining.putIfAbsent(uuid,SHARD_INTERVAL_SECONDS);
        org.bukkit.scoreboard.ScoreboardManager manager=Bukkit.getScoreboardManager();
        if(manager==null)return;
        Scoreboard board=manager.getNewScoreboard();
        Objective objective=board.registerNewObjective("diddyhud","dummy","§c§lDIDDYBLUD §fSMP");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Stable, unique score-entry keys prevent visible flicker on updates.
        String[] entries={"§0","§1","§2","§3","§4","§5","§6","§7"};
        String[] teams={"top","username","money","shards","timer","reward","gap","bottom"};
        for(int i=0;i<teams.length;i++){
            Team team=board.registerNewTeam(teams[i]);
            team.addEntry(entries[i]);
            objective.getScore(entries[i]).setScore(teams.length-i);
        }
        board.getTeam("top").setPrefix("§8§m----------------");
        board.getTeam("username").setPrefix("§fPlayer: §b"+player.getName());
        board.getTeam("reward").setPrefix("§7Reward: §d+10 Shards");
        board.getTeam("gap").setPrefix("§r ");
        board.getTeam("bottom").setPrefix("§8§m----------------");
        sidebars.put(uuid,board);
        updateSidebar(player);
        player.setScoreboard(board);
    }
    private void updateSidebar(Player p){
        Scoreboard board=sidebars.get(p.getUniqueId());
        if(board==null)return;
        long remaining=shardSecondsRemaining.getOrDefault(p.getUniqueId(),SHARD_INTERVAL_SECONDS);
        board.getTeam("money").setPrefix("§aMoney: §f$"+abbreviate(funds(p.getUniqueId())));
        board.getTeam("shards").setPrefix("§bShards: §f"+abbreviate(shards.getOrDefault(p.getUniqueId(),0L)));
        board.getTeam("timer").setPrefix(String.format(Locale.ROOT,
            "§eNext: §f%02d:%02d",remaining/60,remaining%60));
    }
    private static String abbreviate(long value){
        if(value>=1_000_000_000_000L)return String.format(Locale.ROOT,"%.1ft",value/1_000_000_000_000.0);
        if(value>=1_000_000_000L)return String.format(Locale.ROOT,"%.1fb",value/1_000_000_000.0);
        if(value>=1_000_000L)return String.format(Locale.ROOT,"%.1fm",value/1_000_000.0);
        if(value>=10_000L)return String.format(Locale.ROOT,"%.1fk",value/1_000.0);
        return Long.toString(value);
    }
    private void tickShardRewards(){
        boolean payout=false;
        for(Player player:Bukkit.getOnlinePlayers()){
            UUID uuid=player.getUniqueId();
            if(!sidebars.containsKey(uuid))initializeSidebar(player);
            long remaining=shardSecondsRemaining.getOrDefault(uuid,SHARD_INTERVAL_SECONDS)-1L;
            if(remaining<=0){
                long old=shards.getOrDefault(uuid,0L);
                if(old<=Long.MAX_VALUE-SHARD_REWARD){
                    shards.put(uuid,old+SHARD_REWARD);
                    remaining=SHARD_INTERVAL_SECONDS;
                    player.sendMessage("§d§l+10 Shards §7— 30 minutes played!");
                    payout=true;
                }else{
                    // Avoid overflowing a balance; retry after the next second.
                    remaining=0;
                }
            }
            shardSecondsRemaining.put(uuid,remaining);
            updateSidebar(player);
        }
        if(payout)save();
    }
    @EventHandler public void onJoin(PlayerJoinEvent event){
        initializeSidebar(event.getPlayer());
    }
    @EventHandler public void onQuit(PlayerQuitEvent e){
        if(e.getPlayer().getUniqueId().equals(queuePlayer))queuePlayer=null;
        sidebars.remove(e.getPlayer().getUniqueId());
        save();
    }
    @EventHandler public void onDrag(InventoryDragEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof MenuHolder h))return;
        if(h.kind.startsWith("vault:"))return;
        if(h.kind.equals("sell")){
            for(int slot:e.getRawSlots())if(slot>=18&&slot<27){e.setCancelled(true);return;}
            return;
        }
        e.setCancelled(true);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        String cmd=command.getName().toLowerCase(Locale.ROOT);
        if(cmd.equals("diddyblud")){
            sender.sendMessage("§6Diddyblud Lifesteal §fPaper §a"+getDescription().getVersion());
            sender.sendMessage("§7Features: Heart right-click, Halloween GUIs, scoreboard, and shard rewards.");
            return true;
        }
        if(!(sender instanceof Player p)){sender.sendMessage("In-game command required.");return true;}
        UUID uid=p.getUniqueId();
        try{
            switch(cmd){
                case "bal","balance" ->{p.sendMessage("§aBalance: $"+funds(uid));return true;}
                case "shards" ->{
                    if(args.length==0){
                        p.sendMessage("§bShards: "+shards.getOrDefault(uid,0L));return true;
                    }
                    if(!p.hasPermission("diddyblud.admin")){
                        p.sendMessage("§cOnly OPs can change shard balances.");return true;
                    }
                    if(args.length!=3||(!args[0].equalsIgnoreCase("give")&&!args[0].equalsIgnoreCase("set"))){
                        p.sendMessage("Usage: /shards give <player> <amount> or /shards set <player> <amount>");
                        return true;
                    }
                    Player target=Bukkit.getPlayerExact(args[1]);
                    if(target==null){p.sendMessage("§cPlayer must be online.");return true;}
                    boolean setting=args[0].equalsIgnoreCase("set");
                    long amount=setting?parseSetAmount(args[2]):parseAmount(args[2]);
                    long current=shards.getOrDefault(target.getUniqueId(),0L);
                    long updated=setting?amount:Math.addExact(current,amount);
                    shards.put(target.getUniqueId(),updated);
                    save();
                    p.sendMessage("§a"+target.getName()+" now has "+updated+" shards.");
                    target.sendMessage("§bYour shard balance is now "+updated+".");
                    return true;
                }
                case "money" ->{
                    if(!p.hasPermission("diddyblud.admin")){p.sendMessage("§cOP only.");return true;}
                    if(args.length!=3||(!args[0].equalsIgnoreCase("give")&&!args[0].equalsIgnoreCase("set"))){
                        p.sendMessage("Usage: /money give <player> <amount> or /money set <player> <amount>");
                        return true;
                    }
                    Player target=Bukkit.getPlayerExact(args[1]);
                    if(target==null){p.sendMessage("§cPlayer must be online.");return true;}
                    boolean setting=args[0].equalsIgnoreCase("set");
                    long amount=setting?parseSetAmount(args[2]):parseAmount(args[2]);
                    long current=funds(target.getUniqueId());
                    long updated=setting?amount:Math.addExact(current,amount);
                    cash.put(target.getUniqueId(),updated);
                    save();
                    p.sendMessage("§a"+target.getName()+" now has $"+updated+".");
                    target.sendMessage("§aYour money balance is now $"+updated+".");
                    return true;
                }
                case "pay" ->{
                    if(args.length!=2){p.sendMessage("/pay <player> <amount>");return true;}
                    Player target=Bukkit.getPlayerExact(args[0]);if(target==null||target.equals(p))return true;
                    long v=parseAmount(args[1]);
                    if(!charge(uid,v)){p.sendMessage("Not enough money.");return true;}
                    if(!credit(target.getUniqueId(),v)){credit(uid,v);p.sendMessage("Balance overflow.");return true;}
                    p.sendMessage("Paid $"+v+" to "+target.getName());return true;
                }
                case "hearts" ->{
                    if(args.length==0){p.sendMessage("§cHearts: "+maxHearts(p)+" / "+heartCap);return true;}
                    if(!p.hasPermission("diddyblud.admin"))return true;
                    if(args[0].equalsIgnoreCase("max")){
                        if(args.length==2){heartCap=Math.max(1,Math.min(500,Integer.parseInt(args[1])));save();}
                        p.sendMessage("Server maximum: "+heartCap);return true;
                    }
                    if(args.length==3&&(args[0].equalsIgnoreCase("set")||args[0].equalsIgnoreCase("give"))){
                        Player target=Bukkit.getPlayerExact(args[1]);
                        if(target==null){p.sendMessage("§cPlayer must be online.");return true;}
                        int count=Integer.parseInt(args[2]);
                        int resulting=args[0].equalsIgnoreCase("set")?count:Math.addExact(maxHearts(target),count);
                        if(count<1||resulting<1||resulting>heartCap){
                            p.sendMessage("§cHearts must be between 1 and the server maximum ("+heartCap+").");
                            return true;
                        }
                        setHearts(target,resulting);
                        save();
                        p.sendMessage("§aSet "+target.getName()+" to "+resulting+" hearts.");
                        target.sendMessage("§cYour maximum hearts are now "+resulting+".");
                    }else p.sendMessage("Usage: /hearts set <player> <amount>, /hearts give <player> <amount>, /hearts max <amount>");
                    return true;
                }
                case "withdraw" ->{
                    int n=args.length==1?Integer.parseInt(args[0]):1;
                    if(n<1||n>64||maxHearts(p)-n<1){p.sendMessage("Invalid withdrawal.");return true;}
                    setHearts(p,maxHearts(p)-n);give(p,heart(n));return true;
                }
                case "shop" ->{openShop(p,0,false);return true;}
                case "sell" ->{
                    if(args.length==3&&args[0].equalsIgnoreCase("price")&&p.hasPermission("diddyblud.admin")){
                        String id=id(p.getInventory().getItemInMainHand());
                        if(id==null){p.sendMessage("Hold the item.");return true;}
                        buy.put(id,parseAmount(args[1]));sell.put(id,parseAmount(args[2]));
                        assignments.putIfAbsent(id,"storage");save();p.sendMessage("Saved buy/sell for "+id);return true;
                    }
                    openSell(p);return true;
                }
                case "customitems" ->{if(p.hasPermission("diddyblud.admin"))openCustom(p);return true;}
                case "ah","auction" ->{
                    if(args.length==2&&args[0].equalsIgnoreCase("sell")){
                        long cost=parseAmount(args[1]);ItemStack hand=p.getInventory().getItemInMainHand();
                        if(hand.getType().isAir())return true;
                        auctions.put(nextAuction++,new Listing(uid,hand.clone(),cost));
                        p.getInventory().setItemInMainHand(null);save();
                    }else openList(p,"auctions",0);
                    return true;
                }
                case "orders" ->{openList(p,"orders",0);return true;}
                case "order" ->{
                    if(args.length==4&&args[0].equalsIgnoreCase("create")){
                        String item=args[1].toUpperCase(Locale.ROOT);int count=Integer.parseInt(args[2]);
                        long each=parseAmount(args[3]),cost=Math.multiplyExact(each,count);
                        if(count<1||count>64||shopItem(item)==null||!charge(uid,cost))return true;
                        orders.put(nextOrder++,new Order(uid,item,count,cost));save();
                    }else openList(p,"orders",0);
                    return true;
                }
                case "setspawn" ->{
                    if(!p.hasPermission("diddyblud.admin")){
                        p.sendMessage("§cOnly OP players may set the server spawn.");return true;
                    }
                    serverSpawn=p.getLocation().clone();
                    // Also set the vanilla world's spawn as a fallback for players
                    // if the plugin is later disabled.
                    p.getWorld().setSpawnLocation(serverSpawn.getBlockX(),serverSpawn.getBlockY(),serverSpawn.getBlockZ());
                    save();
                    p.sendMessage("§aServer spawn set and saved for everyone.");
                    return true;
                }
                case "spawn" ->{
                    if(serverSpawn==null||serverSpawn.getWorld()==null){
                        p.sendMessage("§cNo server spawn has been set. Ask an OP to use /setspawn.");
                        return true;
                    }
                    if(p.teleport(serverSpawn.clone()))p.sendMessage("§aTeleported to server spawn.");
                    else p.sendMessage("§cUnable to teleport to spawn.");
                    return true;
                }
                case "sethome" ->{
                    if(args.length>1){p.sendMessage("Usage: /sethome [name]");return true;}
                    String name=args.length==0?"home":validHomeName(args[0]);
                    Map<String,Location> saved=homes.computeIfAbsent(uid,k->new LinkedHashMap<>());
                    if(!saved.containsKey(name)&&saved.size()>=10){
                        p.sendMessage("You can save up to 10 homes. Use /delhome <name>.");
                        return true;
                    }
                    saved.put(name,p.getLocation().clone());
                    save();p.sendMessage("Home '"+name+"' saved.");
                    return true;
                }
                case "delhome" ->{
                    if(args.length>1){p.sendMessage("Usage: /delhome [name]");return true;}
                    String name=args.length==0?"home":validHomeName(args[0]);
                    Map<String,Location> saved=homes.get(uid);
                    if(saved==null||saved.remove(name)==null){
                        p.sendMessage("No home named '"+name+"'.");return true;
                    }
                    if(saved.isEmpty())homes.remove(uid);
                    save();p.sendMessage("Deleted home '"+name+"'.");
                    return true;
                }
                case "homes" ->{
                    Map<String,Location> saved=homes.get(uid);
                    p.sendMessage(saved==null||saved.isEmpty()?
                        "You have no saved homes. Use /sethome <name>.":
                        "Homes: "+String.join(", ",saved.keySet()));
                    return true;
                }
                case "home" ->{
                    if(args.length>1){p.sendMessage("Usage: /home [name]");return true;}
                    String name=args.length==0?"home":validHomeName(args[0]);
                    Map<String,Location> saved=homes.get(uid);
                    Location loc=saved==null?null:saved.get(name);
                    if(loc==null){p.sendMessage("No home named '"+name+"'. Use /homes.");return true;}
                    if(loc.getWorld()==null){p.sendMessage("That home's world is unavailable.");return true;}
                    if(p.teleport(loc.clone()))p.sendMessage("Teleported to '"+name+"'.");
                    return true;
                }
                case "tpa" ->{
                    if(args.length!=1)return true;
                    Player target=Bukkit.getPlayerExact(args[0]);
                    if(target!=null&&!target.equals(p)){
                        requests.put(target.getUniqueId(),uid);target.sendMessage(p.getName()+" requests teleport: /tpaccept or /tpdeny");
                    }return true;
                }
                case "tpaccept" ->{
                    UUID source=requests.remove(uid);Player target=source==null?null:Bukkit.getPlayer(source);
                    if(target!=null)target.teleport(p.getLocation());return true;
                }
                case "tpdeny" ->{requests.remove(uid);return true;}
                case "pv","vault" ->{
                    int number=args.length==0?1:Integer.parseInt(args[0]);
                    if(number>=1&&number<=3)openVault(p,number);return true;
                }
                case "rtp" ->{openRtp(p);return true;}
                case "rtpq" ->{
                    if(args.length>0&&args[0].equalsIgnoreCase("leave")){
                        if(uid.equals(queuePlayer)){queuePlayer=null;p.sendMessage("Left the RTP queue.");}
                        return true;
                    }
                    Player queued=queuePlayer==null?null:Bukkit.getPlayer(queuePlayer);
                    if(queued==null){
                        queuePlayer=uid;p.sendMessage("Waiting for a second player. /rtpq leave to cancel.");
                        return true;
                    }
                    if(queued.equals(p)){p.sendMessage("Already in queue.");return true;}
                    queuePlayer=null;
                    World world=p.getWorld();Random rng=new Random();
                    boolean done=false;
                    for(int attempt=0;attempt<20;attempt++){
                        int x=p.getLocation().getBlockX()+rng.nextInt(16000)-8000;
                        int z=p.getLocation().getBlockZ()+rng.nextInt(16000)-8000;
                        int y=world.getHighestBlockYAt(x,z);
                        Material ground=world.getBlockAt(x,y-1,z).getType();
                        if(!ground.isSolid()||ground==Material.LAVA)continue;
                        Location dest=new Location(world,x+0.5,y+1,z+0.5);
                        Location other=dest.clone().add(1,0,0);
                        if(!other.getBlock().isEmpty())continue;
                        p.teleport(dest);queued.teleport(other);
                        p.sendMessage("RTP queue matched with "+queued.getName());
                        queued.sendMessage("RTP queue matched with "+p.getName());
                        done=true;break;
                    }
                    if(!done)p.sendMessage("Couldn't find a safe queue destination.");
                    return true;
                }
                case "spawner" ->{p.sendMessage("Right-click your placed spawner with an empty hand.");return true;}
            }
        }catch(Exception ex){p.sendMessage("§cInvalid command input: "+ex.getMessage());}
        return true;
    }
}
