package com.example.diddybludpaper;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import org.bukkit.block.Block;
import org.bukkit.inventory.meta.Damageable;
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
    private final Map<UUID,Long> leaderboardPlaytime=new HashMap<>(),leaderboardHearts=new HashMap<>();
    private final Map<String,String> rankLabels=new LinkedHashMap<>();
    private final Map<UUID,String> assignedRanks=new HashMap<>();
    // Potion buffs and particles are per-rank, and are not permission grants.
    private final Map<String,Map<String,Integer>> rankPotionEffects=new HashMap<>();
    private final Map<String,String> rankParticleEffects=new HashMap<>();
    private final Map<UUID,Map<String,Integer>> previouslyAppliedRankPotions=new HashMap<>();
    private record KitDefinition(String rank,ItemStack[] slots,Material icon){}
    private final Map<String,KitDefinition> kits=new LinkedHashMap<>();
    private final Map<UUID,String> newKitNames=new HashMap<>();
    private final Map<UUID,String> newKitRanks=new HashMap<>();
    private final Map<UUID,ItemStack[]> editingKits=new HashMap<>();
    private final Map<UUID,String> editingKitNames=new HashMap<>();
    private final Map<UUID,String> editingKitOriginalNames=new HashMap<>();
    private final Map<UUID,Material> editingKitIcons=new HashMap<>();
    private final Set<UUID> awaitingKitRename=new HashSet<>();
    private final Map<UUID,Integer> kitPages=new HashMap<>();
    private final Set<UUID> awaitingKitNames=new HashSet<>();
    private final Set<UUID> lifeLossProcessed=new HashSet<>();
    private LeaderboardHolograms leaderboards;
    private final Map<String,Long> buy=new LinkedHashMap<>(),sell=new LinkedHashMap<>(),shardPrices=new LinkedHashMap<>();
    private final Map<String,String> assignments=new LinkedHashMap<>();
    private final Map<String,Material> categories=new LinkedHashMap<>();
    private final Map<String,String> shopNames=new HashMap<>();
    private final List<String> categoryOrder=new ArrayList<>();
    private final List<String> itemOrder=new ArrayList<>();
    private final Map<UUID,String> pendingShopRename=new HashMap<>();
    private final Map<UUID,String> pendingShopSwap=new HashMap<>();
    private record ShopSelection(String itemId,String category,int page,boolean selling,int quantity){}
    private final Map<UUID,ShopSelection> shopSelections=new HashMap<>();
    private final Set<String> offCategories=new HashSet<>(),offItems=new HashSet<>();
    private final Set<String> deletedShopListings=new HashSet<>();
    private final Map<UUID,Map<String,Location>> homes=new HashMap<>();
    private final Map<UUID,UUID> requests=new HashMap<>();
    private final Map<String,ItemStack[]> vaults=new HashMap<>();
    private final Map<UUID,UUID> viewedInventories=new HashMap<>();
    // Captured ClickVillagers head retains the entire original ItemStack metadata.
    private ItemStack clickVillagerShopTemplate;
    private static final String CLICK_VILLAGER_SHOP_ID="CLICKVILLAGER_CAPTURED";
    private static final String INFINITE_PICKAXE_ID="INFINITE_3X3_PICKAXE";
    private static final String INFINITE_ROCKET_ID="INFINITE_ROCKET";
    private static final String INFINITE_WATER_ID="INFINITE_WATER_BUCKET";
    private boolean miningAdditionalBlocks=false;
    private final Map<Integer,Listing> auctions=new LinkedHashMap<>();
    private final Map<Integer,Order> orders=new LinkedHashMap<>();
    private final Map<UUID,Integer> sellTransactions=new HashMap<>();
    private final Set<UUID> cancelledSellMenus=new HashSet<>();
    // Online playtime in seconds until the next automatic +10 shard reward.
    // Remaining time is saved so logging out or restarting cannot reset the clock.
    private static final long SHARD_INTERVAL_SECONDS=30L*60L;
    private static final long SHARD_REWARD=10L;
    private final Map<UUID,Long> shardSecondsRemaining=new HashMap<>();
    // Offline inventories and Ender Chests are cleared on their next login.
    private final Set<UUID> pendingInventoryWipes=new HashSet<>();
    private record WipeBackup(long money,long points,long playtime,long hearts,long timer,
        Map<String,Location> homes,ItemStack[] inventory,ItemStack[] ender){}
    private final Map<UUID,WipeBackup> wipeBackups=new HashMap<>();
    private final Set<UUID> pendingRestores=new HashSet<>();
    private final Map<UUID,Long> pendingWipeAllConfirmation=new HashMap<>();
    private final Map<UUID,Scoreboard> sidebars=new HashMap<>();
    private int nextAuction=1,nextOrder=1,heartCap=100;
    private UUID queuePlayer=null;
    private NamespacedKey heartKey,spawnerKey,halloweenKey,areaPickaxeKey,infiniteRocketKey,infiniteWaterKey;
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
        areaPickaxeKey=new NamespacedKey(this,"infinite_3x3_pickaxe");
        infiniteRocketKey=new NamespacedKey(this,"infinite_rocket");
        infiniteWaterKey=new NamespacedKey(this,"infinite_water_bucket");
        dataFile=new File(getDataFolder(),"server-data.yml");
        defaults();load();
        for(World world:getServer().getWorlds())setRadarOff(world);
        leaderboards=new LeaderboardHolograms(this,this::leaderboardScores);
        spawners=new PaperSpawners(this,spawnerKey);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getPluginManager().registerEvents(spawners,this);
        getServer().getScheduler().runTaskTimer(this,()->spawners.produce(),100L,100L);
        getServer().getScheduler().runTaskTimer(this,this::save,6000L,6000L);
        // Update the player HUD and online-playtime reward once every second.
        getServer().getScheduler().runTaskTimer(this,this::tickShardRewards,20L,20L);
        getServer().getScheduler().runTaskTimer(this,()->leaderboards.refresh(),40L,400L);
        getServer().getScheduler().runTaskTimer(this,this::refreshRankEffects,20L,80L);
        for(Player online:getServer().getOnlinePlayers())initializeSidebar(online);
        refreshAllRanks();
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
        if(leaderboards!=null)leaderboards.close();
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
            "combat,INFINITE_3X3_PICKAXE,150000,25000",
            "combat,INFINITE_ROCKET,85000,0","combat,INFINITE_WATER_BUCKET,45000,0",
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
            if(!x[1].equals("HEART")&&!x[1].equals(INFINITE_PICKAXE_ID)&&
                !x[1].equals(INFINITE_ROCKET_ID)&&!x[1].equals(INFINITE_WATER_ID)&&
                Material.matchMaterial(x[1])==null)continue;
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
        for(String id:y.getStringList("pending-unwipe-restores"))
            try{pendingRestores.add(UUID.fromString(id));}catch(IllegalArgumentException ignored){}
        ConfigurationSection backups=y.getConfigurationSection("wipe-backups");
        if(backups!=null)for(String raw:backups.getKeys(false))try{
            UUID id=UUID.fromString(raw);
            String base="wipe-backups."+raw+".";
            Map<String,Location> storedHomes=new LinkedHashMap<>();
            ConfigurationSection hs=y.getConfigurationSection(base+"homes");
            if(hs!=null)for(String h:hs.getKeys(false)){
                Location location=hs.getLocation(h);
                if(location!=null)storedHomes.put(h,location);
            }
            wipeBackups.put(id,new WipeBackup(y.getLong(base+"money"),
                y.getLong(base+"points"),y.getLong(base+"playtime"),
                y.getLong(base+"hearts",10),y.getLong(base+"timer",SHARD_INTERVAL_SECONDS),
                storedHomes,readWipeItems(y.getList(base+"inventory")),
                readWipeItems(y.getList(base+"ender"))));
        }catch(IllegalArgumentException ignored){}
        for(String id:y.getStringList("pending-inventory-wipes")){
            try{pendingInventoryWipes.add(UUID.fromString(id));}
            catch(IllegalArgumentException ignored){}
        }
        ConfigurationSection loadedKits=y.getConfigurationSection("kits");
        if(loadedKits!=null)for(String name:loadedKits.getKeys(false)){
            String base="kits."+name+".";
            String required=y.getString(base+"rank","everyone");
            List<?> items=y.getList(base+"slots");
            ItemStack[] slots=new ItemStack[41];
            if(items!=null)for(int i=0;i<Math.min(41,items.size());i++)
                if(items.get(i) instanceof ItemStack item)slots[i]=item.clone();
            Material picture=Material.matchMaterial(y.getString(base+"icon","CHEST"));
            if(picture==null||!picture.isItem())picture=Material.CHEST;
            kits.put(name,new KitDefinition(required,slots,picture));
        }
        ConfigurationSection definedRanks=y.getConfigurationSection("rank-labels");
        if(definedRanks!=null)for(String key:definedRanks.getKeys(false))
            rankLabels.put(key,definedRanks.getString(key,key));
        ConfigurationSection assigned=y.getConfigurationSection("assigned-ranks");
        if(assigned!=null)for(String key:assigned.getKeys(false)){
            try{assignedRanks.put(UUID.fromString(key),assigned.getString(key));}
            catch(IllegalArgumentException ignored){}
        }
        ConfigurationSection allPotions=y.getConfigurationSection("rank-potions");
        if(allPotions!=null)for(String rank:allPotions.getKeys(false)){
            ConfigurationSection effects=allPotions.getConfigurationSection(rank);
            if(effects==null)continue;
            Map<String,Integer> parsed=new HashMap<>();
            for(String effect:effects.getKeys(false)){
                int level=effects.getInt(effect);
                if(level>=1&&level<=10&&resolveRankPotion(effect)!=null)
                    parsed.put(effect,level);
            }
            if(!parsed.isEmpty())rankPotionEffects.put(rank,parsed);
        }
        ConfigurationSection particles=y.getConfigurationSection("rank-particles");
        if(particles!=null)for(String rank:particles.getKeys(false)){
            String particle=particles.getString(rank);
            if(resolveRankParticle(particle)!=null)rankParticleEffects.put(rank,particle);
        }
        ItemStack storedVillager=y.getItemStack("clickvillager-shop-template");
        if(isClickVillagerHead(storedVillager)){
            clickVillagerShopTemplate=storedVillager.clone();
            clickVillagerShopTemplate.setAmount(1);
        }else if(storedVillager!=null){
            getLogger().warning("Ignored unrecognised ClickVillagers head shop template.");
        }
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
        for(String section:new String[]{"cash","shards","leaderboard-playtime","leaderboard-hearts"}){
            ConfigurationSection s=y.getConfigurationSection(section);
            if(s!=null)for(String k:s.getKeys(false)){
                try{(section.equals("cash")?cash:section.equals("shards")?shards:
                    section.equals("leaderboard-playtime")?leaderboardPlaytime:leaderboardHearts)
                    .put(UUID.fromString(k),Math.max(0,s.getLong(k)));}
                catch(Exception ignored){}
            }
        }
        for(String section:new String[]{"buy","sell","shard-prices"}){
            ConfigurationSection s=y.getConfigurationSection(section);
            if(s==null)continue;
            Map<String,Long> target=section.equals("buy")?buy:section.equals("sell")?sell:shardPrices;
            for(String k:s.getKeys(false))target.put(k,Math.max(0,s.getLong(k)));
        }
        ConfigurationSection names=y.getConfigurationSection("shop-display-names");
        if(names!=null)for(String k:names.getKeys(false))shopNames.put(k,names.getString(k,k));
        categoryOrder.addAll(y.getStringList("category-order"));
        itemOrder.addAll(y.getStringList("item-order"));
        ConfigurationSection cs=y.getConfigurationSection("categories");
        if(cs!=null)for(String k:cs.getKeys(false)){
            Material m=Material.matchMaterial(cs.getString(k,""));
            if(m!=null)categories.put(k,m);
        }
        ConfigurationSection as=y.getConfigurationSection("assignment");
        if(as!=null)for(String k:as.getKeys(false))assignments.put(k,as.getString(k));
        offCategories.addAll(y.getStringList("off-categories"));
        offItems.addAll(y.getStringList("off-items"));
        offCategories.clear(); // Re-enable previously disabled normal shop categories.
        offItems.removeIf(this::isOrdinaryShopItem);
        deletedShopListings.addAll(y.getStringList("deleted-shop-listings"));
        for(String removed:deletedShopListings){
            assignments.remove(removed);
            buy.remove(removed);
            sell.remove(removed);
            offItems.remove(removed);
        }
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
        y.set("pending-unwipe-restores",pendingRestores.stream().map(UUID::toString).toList());
        wipeBackups.forEach((id,b)->{
            String base="wipe-backups."+id+".";
            y.set(base+"money",b.money());y.set(base+"points",b.points());
            y.set(base+"playtime",b.playtime());y.set(base+"hearts",b.hearts());
            y.set(base+"timer",b.timer());
            b.homes().forEach((name,loc)->y.set(base+"homes."+name,loc));
            if(b.inventory()!=null)y.set(base+"inventory",Arrays.asList(cloneItems(b.inventory())));
            if(b.ender()!=null)y.set(base+"ender",Arrays.asList(cloneItems(b.ender())));
        });
        y.set("pending-inventory-wipes",
            pendingInventoryWipes.stream().map(UUID::toString).toList());
        kits.forEach((name,kit)->{
            y.set("kits."+name+".rank",kit.rank());
            y.set("kits."+name+".icon",kit.icon().name());
            y.set("kits."+name+".slots",Arrays.asList(cloneItems(kit.slots())));
        });
        rankLabels.forEach((k,v)->y.set("rank-labels."+k,v));
        assignedRanks.forEach((k,v)->y.set("assigned-ranks."+k,v));
        rankPotionEffects.forEach((rank,effects)->effects.forEach((effect,level)->
            y.set("rank-potions."+rank+"."+effect,level)));
        rankParticleEffects.forEach((rank,particle)->y.set("rank-particles."+rank,particle));
        shopNames.forEach((k,v)->y.set("shop-display-names."+k,v));
        y.set("category-order",new ArrayList<>(categoryOrder));
        y.set("item-order",new ArrayList<>(itemOrder));
        y.set("clickvillager-shop-template",clickVillagerShopTemplate==null?null:clickVillagerShopTemplate.clone());
        y.set("server-spawn",serverSpawn);
        cash.forEach((id,v)->y.set("cash."+id,v));
        shards.forEach((id,v)->y.set("shards."+id,v));
        leaderboardPlaytime.forEach((id,v)->y.set("leaderboard-playtime."+id,v));
        leaderboardHearts.forEach((id,v)->y.set("leaderboard-hearts."+id,v));
        shardSecondsRemaining.forEach((id,v)->y.set("shard-reward-seconds."+id,v));
        buy.forEach((id,v)->y.set("buy."+id,v));
        sell.forEach((id,v)->y.set("sell."+id,v));
        shardPrices.forEach((id,v)->y.set("shard-prices."+id,v));
        categories.forEach((id,v)->y.set("categories."+id,v.name()));
        assignments.forEach((id,v)->y.set("assignment."+id,v));
        y.set("off-categories",new ArrayList<>(offCategories));
        y.set("off-items",new ArrayList<>(offItems));
        y.set("deleted-shop-listings",new ArrayList<>(deletedShopListings));
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
    private boolean isClickVillagerHead(ItemStack stack){
        if(stack==null||stack.getType()!=Material.PLAYER_HEAD||!stack.hasItemMeta())return false;
        var container=stack.getItemMeta().getPersistentDataContainer();
        // Do not treat vanilla heads or unrelated custom heads as villagers.
        return container.getKeys().stream().anyMatch(key ->
            key.getNamespace().toLowerCase(Locale.ROOT).contains("clickvillager") ||
            key.getKey().toLowerCase(Locale.ROOT).contains("clickvillager") ||
            key.getKey().toLowerCase(Locale.ROOT).contains("villager_data"));
    }
    private boolean isInfiniteAreaPickaxe(ItemStack stack){
        return stack!=null&&stack.getType()==Material.NETHERITE_PICKAXE&&stack.hasItemMeta()&&
            stack.getItemMeta().getPersistentDataContainer().has(areaPickaxeKey,PersistentDataType.BYTE);
    }
    private ItemStack infiniteAreaPickaxe(){
        ItemStack tool=new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta=tool.getItemMeta();
        meta.setDisplayName("§d§lInfinite 3x3 Pickaxe");
        meta.setLore(List.of("§7Mines a 3x3 square in one swing","§bUnbreakable §8• §7Infinite uses","§7Efficiency V • Unbreaking III"));
        meta.setUnbreakable(true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY,5,true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING,3,true);
        meta.getPersistentDataContainer().set(areaPickaxeKey,PersistentDataType.BYTE,(byte)1);
        tool.setItemMeta(meta);
        return tool;
    }
    private boolean isInfiniteRocket(ItemStack item){
        return item!=null&&item.getType()==Material.FIREWORK_ROCKET&&item.hasItemMeta()&&
            item.getItemMeta().getPersistentDataContainer().has(infiniteRocketKey,PersistentDataType.BYTE);
    }
    private boolean isInfiniteWater(ItemStack item){
        return item!=null&&item.getType()==Material.WATER_BUCKET&&item.hasItemMeta()&&
            item.getItemMeta().getPersistentDataContainer().has(infiniteWaterKey,PersistentDataType.BYTE);
    }
    private ItemStack infiniteRocket(){
        ItemStack item=new ItemStack(Material.FIREWORK_ROCKET);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName("§6§lInfinite Rocket");
        meta.setLore(List.of("§7Unlimited firework rocket uses","§bPerfect for Elytra flight"));
        meta.getPersistentDataContainer().set(infiniteRocketKey,PersistentDataType.BYTE,(byte)1);
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack infiniteWater(){
        ItemStack item=new ItemStack(Material.WATER_BUCKET);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName("§b§lInfinite Water Bucket");
        meta.setLore(List.of("§7Place water without using up the bucket","§bInfinite uses"));
        meta.getPersistentDataContainer().set(infiniteWaterKey,PersistentDataType.BYTE,(byte)1);
        item.setItemMeta(meta);
        return item;
    }
    // Restore a consumed special item only if the original hand slot has become
    // empty/the vanilla replacement. Never overwrite items placed there meanwhile.
    private void restoreSpecialHand(Player player,EquipmentSlot hand,ItemStack original,
                                    Material vanillaReplacement){
        ItemStack held=hand==EquipmentSlot.OFF_HAND?
            player.getInventory().getItemInOffHand():player.getInventory().getItemInMainHand();
        if(held!=null&&!held.getType().isAir()&&held.getType()!=vanillaReplacement)return;
        if(held!=null&&held.getType()==vanillaReplacement&&held.hasItemMeta())return;
        ItemStack restored=original.clone();restored.setAmount(1);
        if(hand==EquipmentSlot.OFF_HAND)player.getInventory().setItemInOffHand(restored);
        else player.getInventory().setItemInMainHand(restored);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onInfiniteRocketUse(PlayerInteractEvent event){
        var action=event.getAction();
        if(action!=org.bukkit.event.block.Action.RIGHT_CLICK_AIR&&
           action!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;
        EquipmentSlot hand=event.getHand();
        if(hand!=EquipmentSlot.HAND&&hand!=EquipmentSlot.OFF_HAND)return;
        Player player=event.getPlayer();
        if(player.getGameMode()==GameMode.CREATIVE||player.getGameMode()==GameMode.SPECTATOR)return;
        ItemStack original=event.getItem();
        if(!isInfiniteRocket(original))return;
        if(player.isGliding())return; // handled by Elytra-specific event
        // Let vanilla launch the rocket normally, including Elytra boosts.
        // Return the single consumed rocket on the next tick.
        ItemStack snapshot=original.clone();
        getServer().getScheduler().runTask(this,()->
            restoreSpecialHand(player,hand,snapshot,Material.AIR));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onInfiniteElytraBoost(PlayerElytraBoostEvent event){
        if(isInfiniteRocket(event.getItemStack()))
            event.setShouldConsume(false);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onInfiniteWaterPlace(PlayerBucketEmptyEvent event){
        Player player=event.getPlayer();
        if(player.getGameMode()==GameMode.CREATIVE||player.getGameMode()==GameMode.SPECTATOR)return;
        EquipmentSlot hand=event.getHand();
        if(hand!=EquipmentSlot.HAND&&hand!=EquipmentSlot.OFF_HAND)return;
        ItemStack original=hand==EquipmentSlot.OFF_HAND?
            player.getInventory().getItemInOffHand():player.getInventory().getItemInMainHand();
        if(!isInfiniteWater(original))return;
        // The bucket event's getItemStack() is the replacement after placement.
        // Keep our fully tagged water bucket as that replacement.
        ItemStack restored=original.clone();
        restored.setAmount(1);
        event.setItemStack(restored);
    }
    private String id(ItemStack stack){
        if(stack==null||stack.getType().isAir())return null;
        if(isHeart(stack))return "HEART";
        if(isInfiniteAreaPickaxe(stack))return INFINITE_PICKAXE_ID;
        if(isInfiniteRocket(stack))return INFINITE_ROCKET_ID;
        if(isInfiniteWater(stack))return INFINITE_WATER_ID;
        if(isClickVillagerHead(stack))return CLICK_VILLAGER_SHOP_ID;
        if(spawners.mobFromItem(stack)!=null)return null; // never sell unique spawner as a vanilla item
        if(stack.getType()==Material.ENCHANTED_BOOK){
            var meta=(org.bukkit.inventory.meta.EnchantmentStorageMeta)stack.getItemMeta();
            if(meta!=null&&meta.getStoredEnchants().size()==1){
                var ench=meta.getStoredEnchants().keySet().iterator().next();
                String key="ENCHANT:"+ench.getKey().getKey();
                if(buy.containsKey(key))return key;
            }
            // Unlisted, multi-enchantment and unusual enchanted books all
            // have a sellable vanilla ENCHANTED_BOOK fallback.
            return "ENCHANTED_BOOK";
        }
        return stack.getType().name();
    }
    private boolean isOrdinaryShopItem(String identifier){
        if(identifier==null||identifier.equals("HEART")||identifier.startsWith("ENCHANT:"))return false;
        Material material=Material.matchMaterial(identifier);
        return material!=null&&material.isItem();
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
        Long saved=leaderboardHearts.get(p.getUniqueId());
        if(saved!=null)return (int)Math.max(1,Math.min(500,saved));
        var a=p.getAttribute(Attribute.MAX_HEALTH);
        return a==null?10:Math.max(1,(int)Math.round(a.getBaseValue()/2));
    }
    private void setHearts(Player p,int value){
        int hearts=Math.max(1,Math.min(heartCap,value));
        leaderboardHearts.put(p.getUniqueId(),(long)hearts);
        var attribute=p.getAttribute(Attribute.MAX_HEALTH);
        if(attribute==null)return;
        // Update the actual Minecraft max-health attribute. This is what
        // determines the visible heart bar, not the tracked leaderboard value.
        if(!p.isDead()){
            attribute.setBaseValue(hearts*2.0);
            if(p.getHealth()>attribute.getValue())
                p.setHealth(attribute.getValue());
        }
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
    public void onDeath(PlayerDeathEvent event){
        Player victim=event.getEntity();
        // Duplicate death callbacks in the same life must never cost hearts twice.
        if(!lifeLossProcessed.add(victim.getUniqueId()))return;
        int before=maxHearts(victim);
        int after=Math.max(1,before-1);

        // All deaths (PvP, mobs, falling, etc.) cost one maximum heart.
        // The last permitted total is one heart; reaching it causes a ban.
        setHearts(victim,after);
        victim.sendMessage("§c§l-1 Heart! §7Remaining maximum hearts: §f"+after);

        // All deaths drop one redeemable heart, not just player kills.
        // A heart lost in the void may be unreachable, like ordinary drops.
        event.getDrops().add(heart(1));
        save();

        if(after<=1){
            String name=victim.getName();
            getServer().getScheduler().runTask(this,()->{
                boolean banned=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "ban "+name+" You reached 1 Lifesteal heart");
                if(!banned)getLogger().warning("Could not issue /ban for "+name);
                if(victim.isOnline())victim.kickPlayer("§cYou reached 1 Lifesteal heart.");
            });
        }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onAreaPickaxeBreak(BlockBreakEvent event){
        if(miningAdditionalBlocks)return;
        Player player=event.getPlayer();
        if(player.getGameMode()==GameMode.CREATIVE||player.getGameMode()==GameMode.SPECTATOR)return;
        ItemStack tool=player.getInventory().getItemInMainHand();
        if(!isInfiniteAreaPickaxe(tool))return;
        Block center=event.getBlock();
        if(!center.isPreferredTool(tool))return;

        // Face-aligned plane: walls use X/Y or Z/Y, floors/ceilings X/Z.
        var dir=player.getEyeLocation().getDirection();
        double ax=Math.abs(dir.getX()),ay=Math.abs(dir.getY()),az=Math.abs(dir.getZ());
        int normal=ay>=ax&&ay>=az?1:ax>=az?0:2;
        miningAdditionalBlocks=true;
        try{
            for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++){
                if(a==0&&b==0)continue;
                int dx=normal==0?0:a;
                int dy=normal==1?0:(normal==0?a:b);
                int dz=normal==2?0:(normal==0?b:a);
                Block other=center.getRelative(dx,dy,dz);
                Material type=other.getType();
                // Don't destroy containers, tile entities, unbreakable blocks,
                // liquids, placed spawners, or player-placed interactive devices.
                if(!type.isBlock()||!type.isSolid()||other.getState() instanceof org.bukkit.block.TileState||
                    type==Material.BEDROCK||type==Material.BARRIER||
                    type==Material.END_PORTAL_FRAME||type==Material.REINFORCED_DEEPSLATE||
                    type==Material.OBSIDIAN&&center.getType()!=Material.OBSIDIAN||
                    !other.isPreferredTool(tool))continue;
                BlockBreakEvent secondary=new BlockBreakEvent(other,player);
                Bukkit.getPluginManager().callEvent(secondary);
                if(secondary.isCancelled())continue;
                if(secondary.isDropItems()){
                    for(ItemStack drop:other.getDrops(tool,player))
                        other.getWorld().dropItemNaturally(other.getLocation(),drop);
                }
                other.setType(Material.AIR,false);
            }
        }finally{
            miningAdditionalBlocks=false;
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
    private List<String> sortedCategories(){
        List<String> all=new ArrayList<>(categories.keySet());
        all.sort(Comparator.comparingInt(k->{
            int i=categoryOrder.indexOf(k);
            return i<0?Integer.MAX_VALUE:i;
        }));
        return all;
    }
    private List<String> sortedItems(String category){
        List<String> ids=new ArrayList<>();
        for(String id:assignments.keySet())
            if(category.equals(assignments.get(id)))ids.add(id);
        ids.sort(Comparator.comparingInt(k->{
            int i=itemOrder.indexOf(k);
            return i<0?Integer.MAX_VALUE:i;
        }));
        return ids;
    }
    private void swapShopKeys(String first,String second,boolean category){
        List<String> order=category?categoryOrder:itemOrder;
        List<String> all=category?sortedCategories():new ArrayList<>(assignments.keySet());
        for(String key:all)if(!order.contains(key))order.add(key);
        int a=order.indexOf(first),b=order.indexOf(second);
        if(a>=0&&b>=0)Collections.swap(order,a,b);
        save();
    }
    private List<String> visibleCategories(int page){
        List<String> all=sortedCategories();
        int start=page==0?0:9+(page-1)*18;
        int size=page==0?9:18;
        if(start>=all.size())return Collections.emptyList();
        return all.subList(start,Math.min(start+size,all.size()));
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void shopRenameChat(AsyncPlayerChatEvent event){
        UUID uuid=event.getPlayer().getUniqueId();
        String key=pendingShopRename.get(uuid);
        if(key==null)return;
        event.setCancelled(true);
        String name=ChatColor.stripColor(event.getMessage()).trim();
        getServer().getScheduler().runTask(this,()->{
            pendingShopRename.remove(uuid);
            Player player=Bukkit.getPlayer(uuid);
            if(player==null)return;
            if(name.equalsIgnoreCase("cancel")){
                player.sendMessage("§eShop rename cancelled.");return;
            }
            if(name.isEmpty()||name.length()>40||name.contains("§")){
                player.sendMessage("§cName must be 1–40 characters.");return;
            }
            if(!player.hasPermission("diddyblud.admin"))return;
            shopNames.put(key,name);save();
            player.sendMessage("§aRenamed shop entry to "+name+". Open /shop to see it.");
        });
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
    private void openKits(Player player,int page){
        Inventory inv=Bukkit.createInventory(new MenuHolder("kits",page,player.getUniqueId()),54,"§6§lKits");
        ((MenuHolder)inv.getHolder()).inv=inv;
        List<String> names=new ArrayList<>(kits.keySet());
        for(int i=0;i<45&&page*45+i<names.size();i++){
            String name=names.get(page*45+i);
            KitDefinition kit=kits.get(name);
            ItemStack icon=icon(kit.icon(),"§e"+name);
            ItemMeta meta=icon.getItemMeta();
            boolean available=kit.rank().equals("everyone")||
                kit.rank().equals(assignedRanks.get(player.getUniqueId()));
            meta.setLore(List.of("§7Rank: §f"+kit.rank(),
                available?"§aClick to claim":"§cRequires rank "+kit.rank(),
                player.hasPermission("diddyblud.admin")?"§eShift-left: edit | Shift-right: delete":"§7"));
            icon.setItemMeta(meta);
            inv.setItem(i,icon);
        }
        inv.setItem(45,icon(Material.ARROW,"Previous page"));
        inv.setItem(53,icon(Material.ARROW,"Next page"));
        if(player.hasPermission("diddyblud.admin"))
            inv.setItem(49,icon(Material.EMERALD_BLOCK,"§aCreate Kit"));
        player.openInventory(inv);
    }
    private void openKitRanks(Player player,int page){
        List<String> options=new ArrayList<>();
        options.add("everyone");
        options.addAll(rankLabels.keySet());
        Inventory inv=Bukkit.createInventory(new MenuHolder("kitranks",page,player.getUniqueId()),54,
            "§6Choose Kit Rank");
        ((MenuHolder)inv.getHolder()).inv=inv;
        for(int i=0;i<45&&page*45+i<options.size();i++){
            String rank=options.get(page*45+i);
            inv.setItem(i,icon(rank.equals("everyone")?Material.GRASS_BLOCK:Material.NAME_TAG,
                "§e"+(rank.equals("everyone")?"Everyone (no rank)":rank)));
        }
        inv.setItem(45,icon(Material.ARROW,"Previous page"));
        inv.setItem(53,icon(Material.ARROW,"Next page"));
        player.openInventory(inv);
    }
    private void showKitSlot(Inventory inv,int slot,ItemStack item){
        if(item!=null&&!item.getType().isAir()){
            inv.setItem(slot,item.clone());return;
        }
        Material material=switch(slot){
            case 36 -> Material.LEATHER_BOOTS;
            case 37 -> Material.LEATHER_LEGGINGS;
            case 38 -> Material.LEATHER_CHESTPLATE;
            case 39 -> Material.LEATHER_HELMET;
            case 40 -> Material.SHIELD;
            default -> null;
        };
        if(material==null){inv.setItem(slot,null);return;}
        String name=switch(slot){
            case 36 -> "§eBoots slot (empty)";
            case 37 -> "§eLeggings slot (empty)";
            case 38 -> "§eChestplate slot (empty)";
            case 39 -> "§eHelmet slot (empty)";
            default -> "§eOff-hand slot (empty)";
        };
        ItemStack placeholder=icon(material,name);
        ItemMeta meta=placeholder.getItemMeta();
        meta.setLore(List.of("§7Click with an item on your cursor to copy it",
            "§7Right-click to clear the slot"));
        placeholder.setItemMeta(meta);
        inv.setItem(slot,placeholder);
    }
    private void openKitEditor(Player player,String kitName,String rank,ItemStack[] initial){
        UUID uuid=player.getUniqueId();
        ItemStack[] template=cloneItems(initial);
        editingKits.put(uuid,template);
        editingKitNames.put(uuid,kitName);
        editingKitOriginalNames.put(uuid,kits.containsKey(kitName)?kitName:null);
        newKitRanks.put(uuid,rank);
        editingKitIcons.put(uuid,kits.containsKey(kitName)?kits.get(kitName).icon():Material.CHEST);
        showKitEditor(player);
    }
    private void showKitEditor(Player player){
        UUID uuid=player.getUniqueId();
        ItemStack[] template=editingKits.get(uuid);
        String kitName=editingKitNames.get(uuid);
        if(template==null||kitName==null)return;
        Inventory inv=Bukkit.createInventory(new MenuHolder("kiteditor",0,uuid),54,
            "§6Edit Kit: "+kitName.substring(0,Math.min(15,kitName.length())));
        ((MenuHolder)inv.getHolder()).inv=inv;
        for(int i=0;i<41;i++)showKitSlot(inv,i,template[i]);
        inv.setItem(41,icon(Material.PAPER,"§7Slots 0–35: inventory + hotbar"));
        inv.setItem(42,icon(Material.IRON_CHESTPLATE,"§7Slots 36–39: boots, leggings, chest, helmet"));
        inv.setItem(43,icon(Material.SHIELD,"§7Slot 40: off-hand"));
        inv.setItem(46,icon(editingKitIcons.getOrDefault(uuid,Material.CHEST),
            "§eChoose Kit Picture (click holding an item)"));
        inv.setItem(47,icon(Material.NAME_TAG,"§eRename Kit: "+kitName));
        inv.setItem(49,icon(Material.EMERALD_BLOCK,"§aSave Kit"));
        inv.setItem(53,icon(Material.BARRIER,"§cCancel"));
        player.openInventory(inv);
    }
    private void applyKit(Player player,String name){
        KitDefinition kit=kits.get(name);
        if(kit==null){player.sendMessage("§cThat kit no longer exists.");return;}
        if(!kit.rank().equals("everyone")&&!kit.rank().equals(assignedRanks.get(player.getUniqueId()))){
            player.sendMessage("§cYou need the "+kit.rank()+" rank to claim this kit.");return;
        }
        PlayerInventory inventory=player.getInventory();
        ItemStack[] slots=kit.slots();
        for(int i=0;i<41;i++){
            ItemStack incoming=slots[i];
            if(incoming==null||incoming.getType().isAir())continue;
            // Stored order: 0–35 inventory, 36–39 armor (boots/leggings/chest/helmet),
            // 40 off-hand. Displaced items are relocated, or dropped if no space.
            ItemStack displaced;
            if(i<36)displaced=inventory.getItem(i);
            else if(i==36)displaced=inventory.getBoots();
            else if(i==37)displaced=inventory.getLeggings();
            else if(i==38)displaced=inventory.getChestplate();
            else if(i==39)displaced=inventory.getHelmet();
            else displaced=inventory.getItemInOffHand();
            // Fill the destination first. Otherwise addItem() could put the
            // displaced item back into its empty old slot, then overwrite it.
            if(i<36)inventory.setItem(i,incoming.clone());
            else if(i==36)inventory.setBoots(incoming.clone());
            else if(i==37)inventory.setLeggings(incoming.clone());
            else if(i==38)inventory.setChestplate(incoming.clone());
            else if(i==39)inventory.setHelmet(incoming.clone());
            else inventory.setItemInOffHand(incoming.clone());
            if(displaced!=null&&!displaced.getType().isAir()){
                for(ItemStack overflow:inventory.addItem(displaced.clone()).values())
                    player.getWorld().dropItemNaturally(player.getLocation(),overflow);
            }
        }
        player.sendMessage("§aClaimed kit "+name+".");
    }
    private void openShop(Player p,int page,boolean admin){
        Inventory inv=menu(admin?"shopadmin":"shop",page,p.getUniqueId());
        List<String> shown=visibleCategories(page);
        for(int i=0;i<shown.size();i++){
            String category=shown.get(i);
            Material material=categories.get(category);
            if(material==null)continue;
            int slot=page==0?FIXED_SLOTS[i]:i;
            inv.setItem(slot,icon(material,(offCategories.contains(category)?"§cOFF §r":"")+shopNames.getOrDefault("cat:"+category,category)));
            if(admin){
                ItemStack display=inv.getItem(slot);
                ItemMeta m=display.getItemMeta();
                m.setLore(List.of("§eShift-left: select / swap category","§eShift-right: rename via chat"));
                display.setItemMeta(m);
            }
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
        List<String> ids=sortedItems(category);
        int start=page*18;
        for(int i=0;i<18&&start+i<ids.size();i++){
            String id=ids.get(start+i);
            if(!buy.containsKey(id))continue;
            ItemStack s=shopItem(id);
            if(s==null)continue;
            ItemMeta meta=s.getItemMeta();meta.setDisplayName((offItems.contains(id)&&!isOrdinaryShopItem(id)?"§cDISABLED ":"")+shopNames.getOrDefault("item:"+id,id)+
               " §aBuy $"+buy.get(id)+" §eSell $"+sell.getOrDefault(id,0L));
            if(admin)meta.setLore(List.of("§eLeft: +$100 buy price","§eRight: +$10 sell price","§eShift-left: select / swap order","§eShift-right: rename via chat","§7Delete: /shopedit deleteitem <id>"));
            s.setItemMeta(meta);inv.setItem(i,s);
        }
        inv.setItem(18,icon(Material.ARROW,"Back"));
        inv.setItem(19,icon(Material.PAPER,"Previous"));
        inv.setItem(25,icon(Material.PAPER,"Next"));
        if(admin)inv.setItem(22,icon(Material.ANVIL,"Add held item to category"));
        inv.setItem(26,icon(Material.EMERALD,"Balance $"+funds(p.getUniqueId())));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void openShopQuantity(Player player,String id,String category,int page,boolean selling,int quantity){
        if(!assignments.containsKey(id)||!buy.containsKey(id))return;
        int amount=Math.max(1,Math.min(64,quantity));
        shopSelections.put(player.getUniqueId(),new ShopSelection(id,category,page,selling,amount));
        Inventory inv=menu("shopqty",0,player.getUniqueId());
        ItemStack preview=shopItem(id);
        if(preview==null){player.sendMessage("§cThat item is currently unavailable.");return;}
        ItemStack displayed=preview.clone();
        displayed.setAmount(Math.min(amount,displayed.getMaxStackSize()));
        ItemMeta meta=displayed.getItemMeta();
        long unit=selling?sell.getOrDefault(id,0L):buy.getOrDefault(id,0L);
        String label=shopNames.getOrDefault("item:"+id,id);
        meta.setDisplayName("§f"+label+" §e×"+amount);
        meta.setLore(List.of("§7Quantity: §f"+amount+"/64",
            "§7Each: §6$"+unit,"§7Total: §6$"+(unit*amount),
            selling?"§cSell to shop":"§aBuy from shop"));
        displayed.setItemMeta(meta);
        inv.setItem(13,displayed);
        inv.setItem(9,icon(Material.RED_STAINED_GLASS_PANE,"§c-10"));
        inv.setItem(10,icon(Material.RED_STAINED_GLASS_PANE,"§c-1"));
        inv.setItem(16,icon(Material.LIME_STAINED_GLASS_PANE,"§a+1"));
        inv.setItem(17,icon(Material.LIME_STAINED_GLASS_PANE,"§a+10"));
        inv.setItem(11,icon(Material.PAPER,"§eSet 1"));
        inv.setItem(15,icon(Material.PAPER,"§eSet 64"));
        inv.setItem(22,icon(Material.EMERALD_BLOCK,selling?"§cCONFIRM SELL":"§aCONFIRM BUY"));
        inv.setItem(18,icon(Material.ARROW,"§eBack"));
        inv.setItem(26,icon(Material.BARRIER,"§cCancel"));
        player.openInventory(inv);
    }
    private void completeShopQuantity(Player p,ShopSelection selected){
        if(selected==null)return;
        String id=selected.itemId();
        int qty=selected.quantity();
        if(qty<1||qty>64||!assignments.containsKey(id)||!buy.containsKey(id)){
            p.sendMessage("§cThis shop listing is unavailable.");return;
        }
        if(offItems.contains(id)&&!isOrdinaryShopItem(id)){
            p.sendMessage("§cThis shop item is disabled.");return;
        }
        long unit=selected.selling()?sell.getOrDefault(id,0L):buy.getOrDefault(id,0L);
        if(unit<0||unit>Long.MAX_VALUE/qty){p.sendMessage("§cInvalid total price.");return;}
        long total=unit*qty;
        if(selected.selling()){
            if(id.equals(CLICK_VILLAGER_SHOP_ID)||id.equals(INFINITE_ROCKET_ID)||id.equals(INFINITE_WATER_ID)||total<=0){
                p.sendMessage("§cThis item cannot be sold to the shop.");return;
            }
            int available=0;
            for(ItemStack item:p.getInventory().getStorageContents())
                if(id.equals(id(item)))available+=item.getAmount();
            if(available<qty){p.sendMessage("§cYou don't have "+qty+" of that item.");return;}
            if(!credit(p.getUniqueId(),total)){p.sendMessage("§cCannot credit that amount.");return;}
            int left=qty;
            ItemStack[] contents=p.getInventory().getStorageContents();
            for(int i=0;i<contents.length&&left>0;i++){
                ItemStack item=contents[i];
                if(!id.equals(id(item)))continue;
                int remove=Math.min(left,item.getAmount());
                if(remove==item.getAmount())contents[i]=null;
                else item.setAmount(item.getAmount()-remove);
                left-=remove;
            }
            p.getInventory().setStorageContents(contents);
            p.sendMessage("§aSold "+qty+" × "+id+" for $"+total+".");
        }else{
            ItemStack prototype=shopItem(id);
            if(prototype==null){p.sendMessage("§cItem currently unavailable.");return;}
            if(!charge(p.getUniqueId(),total)){p.sendMessage("§cNot enough money.");return;}
            int left=qty;
            while(left>0){
                ItemStack stack=prototype.clone();
                int n=Math.min(left,stack.getMaxStackSize());
                stack.setAmount(n);
                give(p,stack);left-=n;
            }
            p.sendMessage("§aBought "+qty+" × "+id+" for $"+total+".");
        }
    }
    private ItemStack shopItem(String id){
        if(id.equals(INFINITE_PICKAXE_ID))return infiniteAreaPickaxe();
        if(id.equals(INFINITE_ROCKET_ID))return infiniteRocket();
        if(id.equals(INFINITE_WATER_ID))return infiniteWater();
        if(id.equals(CLICK_VILLAGER_SHOP_ID)){
            if(clickVillagerShopTemplate==null)return null;
            ItemStack clone=clickVillagerShopTemplate.clone();
            clone.setAmount(1);
            return clone;
        }
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
        cancelledSellMenus.remove(p.getUniqueId());
        Inventory inv=menu("sell",0,p.getUniqueId());
        inv.setItem(22,icon(Material.EMERALD,"CONFIRM sale"));
        inv.setItem(26,icon(Material.BARRIER,"CANCEL — return items"));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void openRtp(Player player){
        Inventory inv=menu("rtp",0,player.getUniqueId());
        inv.setItem(11,icon(Material.GRASS_BLOCK,"§aRTP Overworld"));
        inv.setItem(13,icon(Material.NETHERRACK,"§cRTP Nether"));
        inv.setItem(15,icon(Material.END_STONE,"§dRTP End"));
        inv.setItem(22,icon(Material.BARRIER,"Cancel"));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        player.openInventory(inv);
    }
    private World rtpWorld(World.Environment environment){
        for(World world:Bukkit.getWorlds())
            if(world.getEnvironment()==environment)return world;
        return null;
    }
    private void teleportRandom(Player player,World.Environment environment){
        World world=rtpWorld(environment);
        if(world==null){player.sendMessage("§cThat dimension is not enabled.");return;}
        Random random=new Random();
        int radius=environment==World.Environment.THE_END?4000:6000;
        for(int attempt=0;attempt<160;attempt++){
            int x=random.nextInt(radius*2+1)-radius;
            int z=random.nextInt(radius*2+1)-radius;
            if(Math.abs(x)<400&&Math.abs(z)<400)continue;
            if(!world.getWorldBorder().isInside(new Location(world,x+0.5,64,z+0.5)))continue;
            // The Nether has a bedrock roof, so highestBlockYAt is unsafe.
            // Search the interior from below the roof and verify two air blocks.
            int top=environment==World.Environment.NETHER?
                Math.min(world.getMaxHeight()-3,120):
                Math.min(world.getMaxHeight()-3,world.getHighestBlockYAt(x,z)+1);
            int bottom=environment==World.Environment.NETHER?30:world.getMinHeight()+2;
            for(int y=top;y>=bottom;y--){
                Block floor=world.getBlockAt(x,y-1,z);
                Block feet=world.getBlockAt(x,y,z);
                Block head=world.getBlockAt(x,y+1,z);
                Material under=floor.getType();
                if(!under.isSolid()||under==Material.LAVA||under==Material.MAGMA_BLOCK||
                    under==Material.CACTUS||under==Material.BEDROCK||
                    under==Material.FIRE||under==Material.SOUL_FIRE)continue;
                if(!feet.isEmpty()||!head.isEmpty())continue;
                if(environment==World.Environment.THE_END&&y<45)continue;
                Location dest=new Location(world,x+0.5,y,z+0.5,
                    player.getLocation().getYaw(),player.getLocation().getPitch());
                if(player.teleport(dest)){
                    player.sendMessage("§aRandomly teleported to "+world.getName()+".");
                }else player.sendMessage("§cTeleportation was prevented.");
                return;
            }
        }
        player.sendMessage("§cCould not find a safe location in "+world.getName()+".");
    }
    private void openCustom(Player p){
        Inventory inv=menu("customitems",0,p.getUniqueId());
        inv.setItem(10,heart(1));
        inv.setItem(20,infiniteAreaPickaxe());
        inv.setItem(21,infiniteRocket());
        inv.setItem(22,infiniteWater());
        String[] mobs=PaperSpawners.MOBS;
        for(int i=0;i<mobs.length;i++)inv.setItem(11+i,spawners.spawner(mobs[i],1));
        decorateHalloween(inv,((MenuHolder)inv.getHolder()).kind);
        p.openInventory(inv);
    }
    private void openInventorySee(Player viewer,Player target){
        for(var entry:viewedInventories.entrySet()){
            if(entry.getValue().equals(target.getUniqueId())&&!entry.getKey().equals(viewer.getUniqueId())){
                viewer.sendMessage("§cAnother admin is already viewing that inventory.");return;
            }
        }
        Inventory inv=Bukkit.createInventory(new MenuHolder("invsee",0,viewer.getUniqueId()),
            54,"§6Inventory: "+target.getName().substring(0,Math.min(16,target.getName().length())));
        ((MenuHolder)inv.getHolder()).inv=inv;
        for(int i=0;i<36;i++){
            ItemStack item=target.getInventory().getItem(i);
            if(item!=null)inv.setItem(i,item.clone());
        }
        ItemStack[] armor={
            target.getInventory().getBoots(),target.getInventory().getLeggings(),
            target.getInventory().getChestplate(),target.getInventory().getHelmet(),
            target.getInventory().getItemInOffHand()
        };
        for(int i=0;i<5;i++)if(armor[i]!=null)inv.setItem(36+i,armor[i].clone());
        inv.setItem(45,icon(Material.PAPER,"§7Slots 0–35: inventory / hotbar"));
        inv.setItem(46,icon(Material.IRON_CHESTPLATE,"§7Slots 36–39: boots, leggings, chest, helmet"));
        inv.setItem(47,icon(Material.SHIELD,"§7Slot 40: off-hand"));
        inv.setItem(53,icon(Material.BARRIER,"§cClose"));
        viewedInventories.put(viewer.getUniqueId(),target.getUniqueId());
        viewer.openInventory(inv);
    }
    private void syncInventorySee(Player viewer,Inventory inventory){
        UUID targetId=viewedInventories.get(viewer.getUniqueId());
        Player target=targetId==null?null:Bukkit.getPlayer(targetId);
        if(target==null)return;
        PlayerInventory pi=target.getInventory();
        for(int i=0;i<36;i++){
            ItemStack item=inventory.getItem(i);
            pi.setItem(i,item==null?null:item.clone());
        }
        pi.setBoots(cloneOrNull(inventory.getItem(36)));
        pi.setLeggings(cloneOrNull(inventory.getItem(37)));
        pi.setChestplate(cloneOrNull(inventory.getItem(38)));
        pi.setHelmet(cloneOrNull(inventory.getItem(39)));
        pi.setItemInOffHand(cloneOrNull(inventory.getItem(40)));
        target.updateInventory();
    }
    private ItemStack cloneOrNull(ItemStack value){
        return value==null||value.getType().isAir()?null:value.clone();
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
        if(type.equals("invsee")){
            if(!p.hasPermission("diddyblud.admin")){e.setCancelled(true);return;}
            if(slot>=41&&slot<54){
                e.setCancelled(true);
                if(slot==53)p.closeInventory();
                return;
            }
            if((e.isShiftClick()&&slot>=54)||
                e.getClick()==ClickType.NUMBER_KEY||
                e.getClick()==ClickType.DOUBLE_CLICK||
                e.getClick()==ClickType.SWAP_OFFHAND){
                e.setCancelled(true);return;
            }
            getServer().getScheduler().runTask(this,()->
                {if(viewedInventories.containsKey(p.getUniqueId())&&
                    p.getOpenInventory().getTopInventory()==h.getInventory())
                        syncInventorySee(p,h.getInventory());});
            return;
        }
        if(type.equals("kits")||type.equals("kitranks")||type.equals("kiteditor")){
            // The editor permits picking an item up from the player's bottom
            // inventory. Clicking an editor slot copies the cursor item without
            // consuming it. Shift-click into the top GUI is not permitted.
            if(type.equals("kiteditor")&&slot>=54){
                if(e.isShiftClick())e.setCancelled(true);
                return;
            }
            e.setCancelled(true);
            if(!(p.hasPermission("diddyblud.admin"))&& !type.equals("kits"))return;
            if(type.equals("kits")){
                int page=h.page;
                if(slot==45){openKits(p,Math.max(0,page-1));return;}
                if(slot==53){openKits(p,page+1);return;}
                if(slot==49&&p.hasPermission("diddyblud.admin")){
                    awaitingKitNames.add(p.getUniqueId());p.closeInventory();
                    p.sendMessage("§eType a new kit name in chat, or 'cancel'.");return;
                }
                if(slot>=45)return;
                List<String> all=new ArrayList<>(kits.keySet());
                int index=page*45+slot;
                if(index>=all.size())return;
                String name=all.get(index);
                if(p.hasPermission("diddyblud.admin")&&e.isShiftClick()){
                    if(e.isRightClick()){
                        kits.remove(name);save();openKits(p,page);
                        p.sendMessage("§aDeleted kit "+name+".");return;
                    }
                    KitDefinition definition=kits.get(name);
                    openKitEditor(p,name,definition.rank(),definition.slots());return;
                }
                p.closeInventory();applyKit(p,name);return;
            }
            if(type.equals("kitranks")){
                if(slot==45){openKitRanks(p,Math.max(0,h.page-1));return;}
                if(slot==53){openKitRanks(p,h.page+1);return;}
                if(slot>=45)return;
                List<String> available=new ArrayList<>();
                available.add("everyone");available.addAll(rankLabels.keySet());
                int index=h.page*45+slot;
                if(index>=available.size())return;
                String name=newKitNames.remove(p.getUniqueId());
                if(name==null)return;
                openKitEditor(p,name,available.get(index),new ItemStack[41]);
                return;
            }
            if(type.equals("kiteditor")){
                UUID uuid=p.getUniqueId();
                if(slot==53){
                    editingKits.remove(uuid);
                    editingKitNames.remove(uuid);
                    editingKitOriginalNames.remove(uuid);
                    editingKitIcons.remove(uuid);
                    newKitRanks.remove(uuid);
                    p.closeInventory();p.sendMessage("§eKit changes cancelled.");return;
                }
                if(slot==46){
                    ItemStack cursor=e.getCursor();
                    if(cursor==null||cursor.getType().isAir()||!cursor.getType().isItem()){
                        p.sendMessage("§eHold an in-game item on your cursor, then click the picture button.");return;
                    }
                    editingKitIcons.put(uuid,cursor.getType());
                    e.getView().getTopInventory().setItem(46,
                        icon(cursor.getType(),"§eChoose Kit Picture (click holding an item)"));
                    p.sendMessage("§aKit picture set to "+cursor.getType().name()+".");return;
                }
                if(slot==47){
                    awaitingKitRename.add(uuid);p.closeInventory();
                    p.sendMessage("§eType the new kit name in chat (or cancel).");return;
                }
                if(slot==49){
                    ItemStack[] edited=editingKits.get(uuid);
                    String name=editingKitNames.get(uuid);
                    String rank=newKitRanks.get(uuid);
                    Material picture=editingKitIcons.getOrDefault(uuid,Material.CHEST);
                    if(edited==null||name==null||rank==null)return;
                    String previous=editingKitOriginalNames.get(uuid);
                    if(kits.containsKey(name)&&(previous==null||!previous.equals(name))){
                        p.sendMessage("§cA kit with that name already exists.");return;
                    }
                    editingKits.remove(uuid);
                    editingKitNames.remove(uuid);
                    editingKitOriginalNames.remove(uuid);
                    editingKitIcons.remove(uuid);
                    newKitRanks.remove(uuid);
                    if(previous!=null&&!previous.equals(name))kits.remove(previous);
                    kits.put(name,new KitDefinition(rank,cloneItems(edited),picture));
                    save();p.sendMessage("§aSaved "+name+" kit for "+rank+".");
                    openKits(p,0);return;
                }
                if(slot>=41)return;
                ItemStack[] edited=editingKits.get(uuid);
                if(edited==null)return;
                if(e.isRightClick())edited[slot]=null;
                else{
                    ItemStack cursor=e.getCursor();
                    if(cursor==null||cursor.getType().isAir()){
                        p.sendMessage("§eHold an item on your cursor, then left-click the kit slot.");return;
                    }
                    // Armor and offhand slots are live editors, not GUI controls.
                    // Keep the complete enchantments and item component metadata.
                    edited[slot]=cursor.clone();
                }
                showKitSlot(e.getView().getTopInventory(),slot,edited[slot]);
                return;
            }
        }
        if(type.startsWith("vault:"))return;
        if(type.equals("sell")){
            if(slot>=18&&slot<27){e.setCancelled(true);
                if(slot==26){cancelledSellMenus.add(p.getUniqueId());p.closeInventory();}
                else if(slot==22)confirmSell(p,e.getView().getTopInventory());
            }else if(e.isShiftClick()){
                // Move shift-clicked inventory stacks into the 18 sell slots.
                // Bukkit's default shift-move could also target GUI controls.
                e.setCancelled(true);
                if(slot>=27&&e.getClickedInventory()!=null){
                    Inventory sellInventory=e.getView().getTopInventory();
                    ItemStack clicked=e.getCurrentItem();
                    if(clicked==null||clicked.getType().isAir())return;
                    ItemStack remainder=clicked.clone();
                    for(int index=0;index<18&&remainder.getAmount()>0;index++){
                        ItemStack existing=sellInventory.getItem(index);
                        if(existing==null||existing.getType().isAir())continue;
                        if(!existing.isSimilar(remainder))continue;
                        int canAdd=Math.min(remainder.getAmount(),existing.getMaxStackSize()-existing.getAmount());
                        if(canAdd>0){
                            existing.setAmount(existing.getAmount()+canAdd);
                            remainder.setAmount(remainder.getAmount()-canAdd);
                        }
                    }
                    for(int index=0;index<18&&remainder.getAmount()>0;index++){
                        ItemStack existing=sellInventory.getItem(index);
                        if(existing!=null&&!existing.getType().isAir())continue;
                        int moved=Math.min(remainder.getAmount(),remainder.getMaxStackSize());
                        ItemStack deposit=remainder.clone();deposit.setAmount(moved);
                        sellInventory.setItem(index,deposit);
                        remainder.setAmount(remainder.getAmount()-moved);
                    }
                    if(remainder.getAmount()<=0)e.getClickedInventory().setItem(e.getSlot(),null);
                    else e.getClickedInventory().setItem(e.getSlot(),remainder);
                }
            }
            return;
        }
        e.setCancelled(true);
        if(slot<0||slot>=27)return;
        ItemStack clicked=e.getView().getTopInventory().getItem(slot);
        if(clicked!=null&&clicked.hasItemMeta()&&
            clicked.getItemMeta().getPersistentDataContainer().has(halloweenKey,PersistentDataType.BYTE))
            return; // Halloween filler is decorative, never a shop product or button.
        if(type.equals("shopqty")){
            ShopSelection selection=shopSelections.get(p.getUniqueId());
            if(selection==null){p.closeInventory();return;}
            int q=selection.quantity();
            if(slot==18||slot==26){
                shopSelections.remove(p.getUniqueId());
                if(slot==18)openCategory(p,selection.category(),selection.page(),false);
                else p.closeInventory();
                return;
            }
            if(slot==22){
                shopSelections.remove(p.getUniqueId());
                completeShopQuantity(p,selection);
                openCategory(p,selection.category(),selection.page(),false);
                return;
            }
            int updated=switch(slot){
                case 9 -> q-10;case 10 -> q-1;case 11 -> 1;
                case 15 -> 64;case 16 -> q+1;case 17 -> q+10;
                default -> q;
            };
            if(updated!=q)openShopQuantity(p,selection.itemId(),selection.category(),
                selection.page(),selection.selling(),updated);
            return;
        }
        if(type.equals("shop")||type.equals("shopadmin")){
            boolean admin=type.equals("shopadmin")&&p.hasPermission("diddyblud.admin");
            if(slot==26&&p.hasPermission("diddyblud.admin")){openShop(p,0,!admin);return;}
            if(slot==19&&admin){
                ItemStack held=p.getInventory().getItemInMainHand();
                if(held.getType().isAir()||!held.hasItemMeta()||!held.getItemMeta().hasDisplayName()){
                    p.sendMessage("§cRename the category icon item in an anvil first.");return;
                }
                String name=ChatColor.stripColor(held.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT).replace(' ','_');
                categories.putIfAbsent(name,held.getType());
                if(!categoryOrder.contains(name))categoryOrder.add(name);
                save();openShop(p,h.page,true);return;
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
                String key="cat:"+cat;
                if(e.isRightClick()){
                    pendingShopRename.put(p.getUniqueId(),key);p.closeInventory();
                    p.sendMessage("§eType the new category display name in chat (or cancel).");return;
                }
                String previous=pendingShopSwap.remove(p.getUniqueId());
                if(previous==null||!previous.startsWith("cat:")){
                    pendingShopSwap.put(p.getUniqueId(),key);
                    p.sendMessage("§eNow shift-left another category to swap positions.");return;
                }
                swapShopKeys(previous.substring(4),cat,true);
                openShop(p,h.page,true);return;
            }
            // Every ordinary shop category remains open to all players.
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
                if(held.getType()==Material.PLAYER_HEAD&&!isClickVillagerHead(held)){
                    p.sendMessage("§cThis head has no recognised ClickVillagers data. It cannot be listed as a captured villager.");
                    return;
                }
                String id=id(held);if(id==null){p.sendMessage("§cHold an eligible item.");return;}
                deletedShopListings.remove(id);
                if(id.equals(CLICK_VILLAGER_SHOP_ID)){
                    clickVillagerShopTemplate=held.clone();clickVillagerShopTemplate.setAmount(1);
                    buy.putIfAbsent(id,5000L);sell.put(id,0L); // buy-only for captured villagers
                }else{
                    buy.putIfAbsent(id,100L);sell.putIfAbsent(id,10L);
                }
                assignments.put(id,category);
                if(!itemOrder.contains(id))itemOrder.add(id);
                save();openCategory(p,category,h.page,true);return;
            }
            if(slot>=18)return;
            List<String> ids=sortedItems(category);
            int at=h.page*18+slot;if(at>=ids.size())return;
            String id=ids.get(at);
            if(admin){
                if(e.isShiftClick()){
                    if(e.isRightClick()){
                        pendingShopRename.put(p.getUniqueId(),"item:"+id);
                        p.closeInventory();
                        p.sendMessage("§eType the new item display name in chat (or cancel).");
                        return;
                    }
                    String key="item:"+id;
                    String selected=pendingShopSwap.remove(p.getUniqueId());
                    if(selected==null||!selected.startsWith("item:")){
                        pendingShopSwap.put(p.getUniqueId(),key);
                        p.sendMessage("§eShift-left another item to swap positions.");return;
                    }
                    swapShopKeys(selected.substring(5),id,false);
                    openCategory(p,category,h.page,true);return;
                }
                else if(e.isLeftClick())buy.put(id,buy.getOrDefault(id,0L)+100L);
                else if(e.isRightClick()){
                    if(id.equals(CLICK_VILLAGER_SHOP_ID)){
                        p.sendMessage("§eCaptured villagers are buy-only in /shop to protect villager trade data.");return;
                    }
                    sell.put(id,sell.getOrDefault(id,0L)+10L);
                }
                save();openCategory(p,category,h.page,true);return;
            }
            if(offItems.contains(id)&&!isOrdinaryShopItem(id)){
                p.sendMessage("§cItem disabled.");return;
            }
            if(e.isLeftClick()||e.isRightClick()){
                openShopQuantity(p,id,category,h.page,e.isRightClick(),1);
            }
            return;
        }
        if(slot==26){p.closeInventory();return;}
        if(slot==18){if(h.page==0)openShop(p,0,false);else openList(p,type,h.page-1);return;}
        if(slot==25){openList(p,type,h.page+1);return;}
        if(type.equals("rtp")){
            if(slot==11||slot==13||slot==15){
                p.closeInventory();
                teleportRandom(p,slot==11?World.Environment.NORMAL:
                    slot==13?World.Environment.NETHER:World.Environment.THE_END);
            }
            else if(slot==22)p.closeInventory();
        }else if(type.equals("customitems")){
            if(!p.hasPermission("diddyblud.admin"))return;
            if(slot==10)give(p,heart(1));
            else if(slot==20)give(p,infiniteAreaPickaxe());
            else if(slot==21)give(p,infiniteRocket());
            else if(slot==22)give(p,infiniteWater());
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
        if(identifier==null)return 0;
        if(identifier.equals(CLICK_VILLAGER_SHOP_ID)||identifier.equals(INFINITE_ROCKET_ID)||
            identifier.equals(INFINITE_WATER_ID))return 0;
        // Ordinary vanilla items must remain sellable even when previously
        // disabled via the shop editor. Only special shop items can be disabled.
        boolean enchantedBook=item!=null&&item.getType()==Material.ENCHANTED_BOOK;
        if(!enchantedBook&&offItems.contains(identifier)&&!isOrdinaryShopItem(identifier))return 0;
        if(sell.containsKey(identifier)&&sell.get(identifier)>0)
            return sell.get(identifier);
        // A stale zero-value shop price should not block normal materials.
        // Admin-only and restricted materials remain rejected by fallbackSellPrice.
        if(sell.containsKey(identifier)&&!enchantedBook&&!isOrdinaryShopItem(identifier))
            return 0;
        if(enchantedBook){
            if(item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta meta){
                long price=40;
                for(int level:meta.getStoredEnchants().values())
                    price+=Math.max(1,level)*35L;
                return Math.min(2000L,price);
            }
            return 40;
        }
        return fallbackSellPrice(item);
    }
    private void confirmSell(Player p,Inventory inv){
        processSellItems(p,inv);
        p.closeInventory();
    }
    /**
     * Consume only the eligible contents after funds are verified. Items with
     * no sale value stay in the inventory and are returned on close.
     */
    private void processSellItems(Player p,Inventory inv){
        long total=0;List<Integer> sold=new ArrayList<>();
        int rejected=0;
        LinkedHashSet<String> rejectedTypes=new LinkedHashSet<>();
        for(int slot=0;slot<18;slot++){
            ItemStack item=inv.getItem(slot);
            if(item==null||item.getType().isAir())continue;
            String identifier=id(item);
            long unit=sellValueFor(item,identifier);
            if(unit<=0){
                rejected++;
                if(rejectedTypes.size()<5)rejectedTypes.add(item.getType().name());
                continue;
            }
            try{total=Math.addExact(total,Math.multiplyExact(unit,item.getAmount()));}
            catch(ArithmeticException ex){
                p.sendMessage("§cSale too large; no items were sold.");return;
            }
            sold.add(slot);
        }
        if(total==0){
            if(rejected>0)p.sendMessage("§cUnsellable: "+String.join(", ",rejectedTypes)+". Returned to inventory.");
            return;
        }
        if(funds(p.getUniqueId())>Long.MAX_VALUE-total){
            p.sendMessage("§cBalance limit reached; no items were sold.");return;
        }
        if(!credit(p.getUniqueId(),total)){
            p.sendMessage("§cCould not deposit sale proceeds; items were returned.");return;
        }
        for(int slot:sold)inv.setItem(slot,null);
        p.sendMessage("§aSold "+sold.size()+" stack(s) for $"+total+".");
        if(rejected>0)p.sendMessage("§e"+rejected+" unsellable stack(s) returned: "+String.join(", ",rejectedTypes));
    }
    @EventHandler public void close(InventoryCloseEvent e){
        if(!(e.getPlayer() instanceof Player p))return;
        if(!(e.getInventory().getHolder() instanceof MenuHolder h))return;
        if(h.kind.equals("invsee")){
            syncInventorySee(p,e.getInventory());
            viewedInventories.remove(p.getUniqueId());
            return;
        }
        if(h.kind.equals("sell")){
            // An ordinary close also sells accepted items; the Cancel button
            // deliberately bypasses sale and returns the full inventory.
            if(!cancelledSellMenus.remove(p.getUniqueId()))
                processSellItems(p,e.getInventory());
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
        // Paper can recreate the player health attributes on respawn.
        // Restore the saved max-hearts after the respawn is finished.
        Player player=event.getPlayer();
        getServer().getScheduler().runTaskLater(this,()->{
            lifeLossProcessed.remove(player.getUniqueId());
            if(player.isOnline()){
                setHearts(player,maxHearts(player));
                player.sendMessage("§eMaximum hearts: "+maxHearts(player));
            }
        },2L);
        if(serverSpawn!=null&&serverSpawn.getWorld()!=null)
            event.setRespawnLocation(serverSpawn.clone());
    }
    // Uses vanilla scoreboard packets: no client mod or texture pack needed.
    private Map<UUID,Long> leaderboardScores(String type){
        if(type.equals("money"))return new HashMap<>(cash);
        if(type.equals("playtime"))return new HashMap<>(leaderboardPlaytime);
        return new HashMap<>(leaderboardHearts);
    }
    // Every player uses a private scoreboard for the Lifesteal sidebar.
    // Add the same rank teams to every scoreboard so everyone sees prefixes.
    private void updateRankTeams(Scoreboard board){
        for(Team team:new ArrayList<>(board.getTeams()))
            if(team.getName().startsWith("dbr_"))team.unregister();
        for(Player target:Bukkit.getOnlinePlayers()){
            String rank=assignedRanks.get(target.getUniqueId());
            if(rank==null||!rankLabels.containsKey(rank))continue;
            String label=ChatColor.translateAlternateColorCodes('&',rankLabels.get(rank));
            String teamName="dbr_"+target.getUniqueId().toString().replace("-","").substring(0,12);
            Team team=board.registerNewTeam(teamName);
            team.setPrefix(label+" §r");
            team.addEntry(target.getName());
        }
    }
    private void updateRankTabName(Player player){
        String id=assignedRanks.get(player.getUniqueId());
        String label=id==null?null:rankLabels.get(id);
        player.setPlayerListName(label==null?player.getName():
            ChatColor.translateAlternateColorCodes('&',label)+" §r"+player.getName());
    }
    private void refreshAllRanks(){
        for(Player viewer:Bukkit.getOnlinePlayers()){
            Scoreboard board=sidebars.get(viewer.getUniqueId());
            if(board!=null)updateRankTeams(board);
            updateRankTabName(viewer);
        }
    }
    private PotionEffectType resolveRankPotion(String id){
        if(id==null)return null;
        // Namespaced lookup accepts Minecraft's canonical potion IDs, for
        // example speed, haste, strength, night_vision and fire_resistance.
        return Registry.MOB_EFFECT.get(NamespacedKey.minecraft(id.toLowerCase(Locale.ROOT)));
    }
    private Particle resolveRankParticle(String id){
        if(id==null)return null;
        try{
            Particle particle=Particle.valueOf(id.toUpperCase(Locale.ROOT));
            // These safe cosmetic particles have no special data payload.
            return switch(particle){
                case FLAME, SOUL_FIRE_FLAME, HEART, HAPPY_VILLAGER,
                     END_ROD, ENCHANT, PORTAL, TOTEM_OF_UNDYING, CLOUD,
                     CRIT, WITCH, NOTE, FIREWORK, GLOW, NAUTILUS -> particle;
                default -> null;
            };
        }catch(IllegalArgumentException ex){return null;}
    }
    private void clearPreviousRankEffects(Player player){
        Map<String,Integer> last=previouslyAppliedRankPotions.remove(player.getUniqueId());
        if(last==null)return;
        for(var entry:last.entrySet()){
            PotionEffectType type=resolveRankPotion(entry.getKey());
            if(type==null)continue;
            PotionEffect active=player.getPotionEffect(type);
            if(active!=null&&active.getAmplifier()==entry.getValue()-1&&
                active.getDuration()<=220){
                player.removePotionEffect(type);
            }
        }
    }
    private void refreshRankEffects(){
        for(Player player:Bukkit.getOnlinePlayers()){
            if(player.isDead())continue;
            String rank=assignedRanks.get(player.getUniqueId());
            Map<String,Integer> configured=rank==null?Map.of():
                rankPotionEffects.getOrDefault(rank,Map.of());
            Map<String,Integer> previous=previouslyAppliedRankPotions
                .getOrDefault(player.getUniqueId(),Map.of());
            for(var old:previous.entrySet()){
                Integer next=configured.get(old.getKey());
                if(next!=null&&next.equals(old.getValue()))continue;
                PotionEffectType type=resolveRankPotion(old.getKey());
                if(type==null)continue;
                PotionEffect active=player.getPotionEffect(type);
                if(active!=null&&active.getAmplifier()==old.getValue()-1&&
                    active.getDuration()<=220)player.removePotionEffect(type);
            }
            Map<String,Integer> applied=new HashMap<>();
            for(var entry:configured.entrySet()){
                PotionEffectType type=resolveRankPotion(entry.getKey());
                if(type==null)continue;
                PotionEffect active=player.getPotionEffect(type);
                int amplifier=entry.getValue()-1;
                // Don't overwrite a stronger buff from a potion or another plugin.
                if(active!=null&&active.getAmplifier()>amplifier)continue;
                player.addPotionEffect(new PotionEffect(type,140,amplifier,true,false,true));
                applied.put(entry.getKey(),entry.getValue());
            }
            if(applied.isEmpty())previouslyAppliedRankPotions.remove(player.getUniqueId());
            else previouslyAppliedRankPotions.put(player.getUniqueId(),applied);
            Particle particle=rank==null?null:resolveRankParticle(rankParticleEffects.get(rank));
            if(particle!=null){
                player.getWorld().spawnParticle(particle,
                    player.getLocation().add(0,1,0),8,0.35,0.65,0.35,0.01);
            }
        }
    }
    private boolean rankCommand(CommandSender sender,String[] args){
        if(!sender.hasPermission("diddyblud.admin")){
            sender.sendMessage("§cOnly OPs can manage ranks.");return true;
        }
        if(args.length==0||args[0].equalsIgnoreCase("help")){
            sender.sendMessage("§e/rank create <id> <display name> (supports & color codes)");
            sender.sendMessage("§e/rank color <id> <red|gold|green|blue|aqua|...|&a>");
            sender.sendMessage("§e/rank effect add <rank> <potion> [level 1–10]");
            sender.sendMessage("§e/rank effect remove <rank> <potion> | /rank effect list <rank>");
            sender.sendMessage("§e/rank particle <rank> <particle|none>");
            sender.sendMessage("§e/rank set <onlinePlayer> <id>");
            sender.sendMessage("§e/rank remove <onlinePlayer>");
            sender.sendMessage("§e/rank delete <id> | /rank list");
            return true;
        }
        String action=args[0].toLowerCase(Locale.ROOT);
        if(action.equals("list")){
            sender.sendMessage("§eRanks: "+(rankLabels.isEmpty()?"none":String.join(", ",rankLabels.keySet())));
            return true;
        }
        if(action.equals("create")&&args.length>=3){
            String id=args[1].toLowerCase(Locale.ROOT);
            if(!id.matches("[a-z0-9_-]{1,24}")){
                sender.sendMessage("§cRank ID must be 1–24 letters, numbers, _ or -.");return true;
            }
            String label=String.join(" ",Arrays.copyOfRange(args,2,args.length));
            if(label.length()>48){
                sender.sendMessage("§cRank display name is too long (48 characters maximum).");return true;
            }
            rankLabels.put(id,label);save();refreshAllRanks();
            sender.sendMessage("§aCreated/updated rank "+id+".");return true;
        }
        if((action.equals("color")||action.equals("colour"))&&args.length==3){
            String id=args[1].toLowerCase(Locale.ROOT);
            String current=rankLabels.get(id);
            if(current==null){sender.sendMessage("§cUnknown rank. Use /rank list.");return true;}
            String choice=args[2].toLowerCase(Locale.ROOT).replace('-','_');
            ChatColor color=null;
            if(choice.matches("&[0-9a-f]")){
                color=ChatColor.getByChar(choice.charAt(1));
            }else{
                try{color=ChatColor.valueOf(choice.toUpperCase(Locale.ROOT));}
                catch(IllegalArgumentException ignored){}
            }
            if(color==null||!color.isColor()){
                sender.sendMessage("§cUse a Minecraft color name like red, dark_red, gold, green,");
                sender.sendMessage("§caqua, blue, light_purple, white, or a code like &a.");
                return true;
            }
            // Remove old colors, but keep formatting such as bold (&l).
            String plain=current.replaceAll("(?i)[&§][0-9a-f]","");
            rankLabels.put(id,"&"+color.getChar()+plain);
            save();refreshAllRanks();
            sender.sendMessage("§aUpdated "+id+" rank color to "+color+choice+"§a.");
            return true;
        }
        if(action.equals("effect")){
            if(args.length<3){
                sender.sendMessage("§e/rank effect add <rank> <effect> [level]");
                sender.sendMessage("§e/rank effect remove <rank> <effect> | /rank effect list <rank>");
                return true;
            }
            String operation=args[1].toLowerCase(Locale.ROOT);
            String rank=args[2].toLowerCase(Locale.ROOT);
            if(!rankLabels.containsKey(rank)){
                sender.sendMessage("§cUnknown rank. Use /rank list.");return true;
            }
            if(operation.equals("list")&&args.length==3){
                sender.sendMessage("§e"+rank+" potion effects: "+
                    rankPotionEffects.getOrDefault(rank,Map.of()));
                return true;
            }
            if(operation.equals("remove")&&args.length==4){
                String potion=args[3].toLowerCase(Locale.ROOT);
                Map<String,Integer> effects=rankPotionEffects.get(rank);
                if(effects!=null){
                    effects.remove(potion);
                    if(effects.isEmpty())rankPotionEffects.remove(rank);
                }
                save();refreshRankEffects();
                sender.sendMessage("§aRemoved "+potion+" from "+rank+".");return true;
            }
            if(operation.equals("add")&&(args.length==4||args.length==5)){
                String potion=args[3].toLowerCase(Locale.ROOT);
                if(resolveRankPotion(potion)==null){
                    sender.sendMessage("§cUnknown potion effect. Examples: speed, haste,");
                    sender.sendMessage("§cstrength, night_vision, fire_resistance, jump_boost.");
                    return true;
                }
                int level=1;
                if(args.length==5){
                    try{level=Integer.parseInt(args[4]);}
                    catch(NumberFormatException ex){
                        sender.sendMessage("§cLevel must be 1–10.");return true;
                    }
                }
                if(level<1||level>10){
                    sender.sendMessage("§cLevel must be 1–10.");return true;
                }
                rankPotionEffects.computeIfAbsent(rank,k->new HashMap<>()).put(potion,level);
                save();refreshRankEffects();
                sender.sendMessage("§aAdded "+potion+" "+level+" to "+rank+".");return true;
            }
            sender.sendMessage("§cInvalid syntax. Use /rank help.");return true;
        }
        if(action.equals("particle")&&args.length==3){
            String rank=args[1].toLowerCase(Locale.ROOT);
            if(!rankLabels.containsKey(rank)){
                sender.sendMessage("§cUnknown rank.");return true;
            }
            String particle=args[2].toUpperCase(Locale.ROOT);
            if(particle.equals("NONE")||particle.equals("OFF")){
                rankParticleEffects.remove(rank);
            }else if(resolveRankParticle(particle)!=null){
                rankParticleEffects.put(rank,particle);
            }else{
                sender.sendMessage("§cTry FLAME, HEART, END_ROD, PORTAL, ENCHANT,");
                sender.sendMessage("§cTOTEM_OF_UNDYING, CLOUD, or NONE.");return true;
            }
            save();
            sender.sendMessage("§aUpdated particle effect for "+rank+".");return true;
        }
        if(action.equals("delete")&&args.length==2){
            String id=args[1].toLowerCase(Locale.ROOT);
            if(rankLabels.remove(id)==null){sender.sendMessage("§cUnknown rank.");return true;}
            assignedRanks.values().removeIf(id::equals);
            rankPotionEffects.remove(id);
            rankParticleEffects.remove(id);
            save();refreshAllRanks();refreshRankEffects();
            sender.sendMessage("§aDeleted rank "+id+".");return true;
        }
        if((action.equals("set")&&args.length==3)||(action.equals("remove")&&args.length==2)){
            Player target=Bukkit.getPlayerExact(args[1]);
            if(target==null){sender.sendMessage("§cPlayer must be online.");return true;}
            if(action.equals("set")){
                String id=args[2].toLowerCase(Locale.ROOT);
                if(!rankLabels.containsKey(id)){sender.sendMessage("§cUnknown rank. Use /rank list.");return true;}
                assignedRanks.put(target.getUniqueId(),id);
            }else assignedRanks.remove(target.getUniqueId());
            save();refreshAllRanks();refreshRankEffects();
            sender.sendMessage("§aUpdated rank for "+target.getName()+".");return true;
        }
        sender.sendMessage("§cInvalid syntax. Use /rank help.");return true;
    }
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
        updateRankTeams(board);
        player.setScoreboard(board);
        updateRankTabName(player);
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
            try{
            UUID uuid=player.getUniqueId();
            leaderboardPlaytime.merge(uuid,1L,Long::sum);
            // Respawn, dimension changes or other plugins can reset max health
            // to 20 HP. Reapply saved Lifesteal hearts without changing count.
            var maxAttribute=player.getAttribute(Attribute.MAX_HEALTH);
            double expectedHealth=maxHearts(player)*2.0;
            if(maxAttribute!=null&&!player.isDead()&&
                Math.abs(maxAttribute.getBaseValue()-expectedHealth)>0.01)
                setHearts(player,maxHearts(player));
            if(!sidebars.containsKey(uuid))initializeSidebar(player);
            // Other plugins can replace a player's entire scoreboard; restore
            // our sidebar without resetting reward timers or recreating it.
            Scoreboard expected=sidebars.get(uuid);
            if(expected!=null){
                Objective objective=expected.getObjective("diddyhud");
                if(objective==null||objective.getDisplaySlot()!=DisplaySlot.SIDEBAR){
                    initializeSidebar(player);
                }else if(player.getScoreboard()!=expected){
                    player.setScoreboard(expected);
                }
            }
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
            }catch(Exception ex){
                getLogger().warning("HUD update failed for "+player.getName()+": "+ex.getMessage());
            }
        }
        if(payout)save();
    }
    @EventHandler public void onJoin(PlayerJoinEvent event){
        Player player=event.getPlayer();
        if(pendingInventoryWipes.contains(player.getUniqueId())){
            backupWipe(player.getUniqueId(),player);
            clearWipedInventory(player);
            save();
        }
        if(pendingRestores.contains(player.getUniqueId()))finishUnwipe(player);
        int savedHearts=maxHearts(player);
        setHearts(player,savedHearts);
        leaderboardPlaytime.putIfAbsent(player.getUniqueId(),0L);
        // Let other join handlers finish first; recurring tick also repairs a
        // scoreboard replaced after this delayed first refresh.
        getServer().getScheduler().runTask(this,()->{
            if(player.isOnline()){
                initializeSidebar(player);
                refreshAllRanks();
            }
        });
    }
    @EventHandler public void onQuit(PlayerQuitEvent e){
        if(e.getPlayer().getUniqueId().equals(queuePlayer))queuePlayer=null;
        for(var entry:new ArrayList<>(viewedInventories.entrySet())){
            if(entry.getValue().equals(e.getPlayer().getUniqueId())){
                Player viewer=Bukkit.getPlayer(entry.getKey());
                if(viewer!=null)viewer.closeInventory();
            }
        }
        viewedInventories.remove(e.getPlayer().getUniqueId());
        sidebars.remove(e.getPlayer().getUniqueId());
        shopSelections.remove(e.getPlayer().getUniqueId());
        lifeLossProcessed.remove(e.getPlayer().getUniqueId());
        previouslyAppliedRankPotions.remove(e.getPlayer().getUniqueId());
        getServer().getScheduler().runTask(this,this::refreshAllRanks);
        save();
    }
    @EventHandler public void onDrag(InventoryDragEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof MenuHolder h))return;
        if(h.kind.startsWith("vault:"))return;
        if(h.kind.equals("invsee")){
            for(int slot:e.getRawSlots())if(slot>=41&&slot<54){
                e.setCancelled(true);return;
            }
            if(e.getWhoClicked() instanceof Player viewer){
                getServer().getScheduler().runTask(this,()->
                    {if(viewedInventories.containsKey(viewer.getUniqueId()))
                        syncInventorySee(viewer,e.getInventory());});
            }
            return;
        }
        if(h.kind.equals("kits")||h.kind.equals("kitranks")||h.kind.equals("kiteditor")){
            e.setCancelled(true);return;
        }
        if(h.kind.equals("sell")){
            for(int slot:e.getRawSlots())if(slot>=18&&slot<27){e.setCancelled(true);return;}
            return;
        }
        e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void kitNameChat(AsyncPlayerChatEvent event){
        UUID uuid=event.getPlayer().getUniqueId();
        if(!awaitingKitNames.contains(uuid)&&!awaitingKitRename.contains(uuid))return;
        event.setCancelled(true);
        String name=event.getMessage().trim().toLowerCase(Locale.ROOT);
        getServer().getScheduler().runTask(this,()->{
            boolean renaming=awaitingKitRename.remove(uuid);
            awaitingKitNames.remove(uuid);
            Player player=Bukkit.getPlayer(uuid);
            if(player==null)return;
            if(name.equals("cancel")){
                if(renaming){showKitEditor(player);return;}
                player.sendMessage("§eKit creation cancelled.");return;
            }
            if(!name.matches("[a-z0-9_-]{1,24}")){
                player.sendMessage("§cKit name must be 1–24 letters, numbers, _ or -.");return;
            }
            if(renaming){
                String original=editingKitOriginalNames.get(uuid);
                if(kits.containsKey(name)&&!name.equals(original)){
                    player.sendMessage("§cThat kit name already exists.");showKitEditor(player);return;
                }
                editingKitNames.put(uuid,name);
                showKitEditor(player);return;
            }
            if(kits.containsKey(name)){player.sendMessage("§cThat kit already exists.");return;}
            newKitNames.put(uuid,name);
            openKitRanks(player,0);
        });
    }
    private static ItemStack[] readWipeItems(List<?> items){
        if(items==null)return null;
        ItemStack[] result=new ItemStack[items.size()];
        for(int i=0;i<items.size();i++)if(items.get(i) instanceof ItemStack item)
            result[i]=item.clone();
        return result;
    }
    private OfflinePlayer knownPlayer(String name){
        Player online=Bukkit.getPlayerExact(name);
        if(online!=null)return online;
        for(OfflinePlayer old:Bukkit.getOfflinePlayers())
            if(old.getName()!=null&&old.getName().equalsIgnoreCase(name))return old;
        return null;
    }
    private void backupWipe(UUID uuid,Player player){
        WipeBackup old=wipeBackups.get(uuid);
        if(old!=null){
            if(old.inventory()==null&&player!=null)
                wipeBackups.put(uuid,new WipeBackup(old.money(),old.points(),
                    old.playtime(),old.hearts(),old.timer(),old.homes(),
                    cloneItems(player.getInventory().getContents()),
                    cloneItems(player.getEnderChest().getContents())));
            return;
        }
        Map<String,Location> locations=new LinkedHashMap<>();
        homes.getOrDefault(uuid,Map.of()).forEach((name,loc)->locations.put(name,loc.clone()));
        wipeBackups.put(uuid,new WipeBackup(funds(uuid),shards.getOrDefault(uuid,0L),
            leaderboardPlaytime.getOrDefault(uuid,0L),leaderboardHearts.getOrDefault(uuid,10L),
            shardSecondsRemaining.getOrDefault(uuid,SHARD_INTERVAL_SECONDS),locations,
            player==null?null:cloneItems(player.getInventory().getContents()),
            player==null?null:cloneItems(player.getEnderChest().getContents())));
    }
    private void finishUnwipe(Player player){
        UUID id=player.getUniqueId();
        WipeBackup old=wipeBackups.get(id);
        if(old!=null&&old.inventory()!=null&&old.ender()!=null){
            player.closeInventory();
            player.getInventory().setContents(cloneItems(old.inventory()));
            player.getEnderChest().setContents(cloneItems(old.ender()));
            player.updateInventory();
        }
        pendingRestores.remove(id);
        wipeBackups.remove(id);
    }
    private boolean unwipeCommand(CommandSender sender,String[] args){
        if(!sender.hasPermission("diddyblud.admin")){
            sender.sendMessage("§cOP only.");return true;
        }
        if(args.length!=1){sender.sendMessage("§eUsage: /unwipe <player>");return true;}
        OfflinePlayer target=knownPlayer(args[0]);
        if(target==null){sender.sendMessage("§cUnknown player.");return true;}
        UUID id=target.getUniqueId();
        WipeBackup old=wipeBackups.get(id);
        if(old==null){sender.sendMessage("§cNo backup exists from a wipe made with this version.");return true;}
        cash.put(id,old.money());shards.put(id,old.points());
        leaderboardPlaytime.put(id,old.playtime());leaderboardHearts.put(id,old.hearts());
        shardSecondsRemaining.put(id,old.timer());
        if(old.homes().isEmpty())homes.remove(id);
        else homes.put(id,new LinkedHashMap<>(old.homes()));
        pendingInventoryWipes.remove(id);
        Player player=Bukkit.getPlayer(id);
        if(old.inventory()!=null&&old.ender()!=null){
            if(player!=null)finishUnwipe(player);
            else pendingRestores.add(id);
        }else wipeBackups.remove(id); // Offline wipe not executed on inventory yet.
        if(player!=null){
            setHearts(player,(int)Math.max(1,Math.min(heartCap,old.hearts())));
            updateSidebar(player);
        }
        save();
        if(leaderboards!=null)leaderboards.refresh();
        sender.sendMessage("§aRestored pre-wipe data for "+target.getName()+".");
        return true;
    }
    private void clearWipedInventory(Player player){
        // Close open vaults before wiping, so closing them cannot restore items.
        // Wipe covers main inventory, hotbar, armor, off-hand and Ender Chest.
        player.closeInventory();
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setItemOnCursor(null);
        player.updateInventory();
        pendingInventoryWipes.remove(player.getUniqueId());
    }
    private void wipePlayerData(UUID playerId){
        Player online=Bukkit.getPlayer(playerId);
        backupWipe(playerId,online);
        pendingRestores.remove(playerId);
        cash.remove(playerId);
        shards.remove(playerId);
        homes.remove(playerId);
        leaderboardPlaytime.put(playerId,0L);
        leaderboardHearts.put(playerId,10L);
        shardSecondsRemaining.remove(playerId);
        requests.remove(playerId);
        requests.values().removeIf(playerId::equals);
        pendingInventoryWipes.add(playerId);
        if(online!=null){
            clearWipedInventory(online);
            setHearts(online,10);
            updateSidebar(online);
            online.sendMessage("§cAn administrator has wiped your inventory, Ender Chest, homes, money, shards, hearts and playtime.");
        }
    }
    private boolean wipeCommand(CommandSender sender,String[] args){
        if(!sender.hasPermission("diddyblud.admin")){
            sender.sendMessage("§cOnly OPs may use /wipe.");return true;
        }
        if(args.length==0||args.length>2){
            sender.sendMessage("§eUsage: /wipe <player> or /wipe all");
            sender.sendMessage("§eConfirm a full reset with /wipe all confirm.");return true;
        }
        if(args[0].equalsIgnoreCase("all")){
            if(args.length==1){
                if(!(sender instanceof Player player)){
                    sender.sendMessage("§cRun /wipe all confirm from the console.");return true;
                }
                pendingWipeAllConfirmation.put(player.getUniqueId(),
                    System.currentTimeMillis()+60000L);
                sender.sendMessage("§4WARNING: This will wipe EVERY player, including offline players.");
                sender.sendMessage("§cType /wipe all confirm within 60 seconds to proceed.");
                return true;
            }
            if(!args[1].equalsIgnoreCase("confirm")){
                sender.sendMessage("§cUse /wipe all confirm.");return true;
            }
            if(sender instanceof Player player){
                Long expiry=pendingWipeAllConfirmation.remove(player.getUniqueId());
                if(expiry==null||System.currentTimeMillis()>expiry){
                    sender.sendMessage("§cConfirmation expired. Run /wipe all first.");return true;
                }
            }
            Set<UUID> targets=new HashSet<>();
            for(OfflinePlayer offline:Bukkit.getOfflinePlayers())targets.add(offline.getUniqueId());
            for(Player online:Bukkit.getOnlinePlayers())targets.add(online.getUniqueId());
            targets.addAll(cash.keySet());
            targets.addAll(shards.keySet());
            targets.addAll(homes.keySet());
            targets.addAll(leaderboardHearts.keySet());
            targets.addAll(leaderboardPlaytime.keySet());
            targets.addAll(pendingInventoryWipes);
            for(UUID uuid:targets)wipePlayerData(uuid);
            save();
            if(leaderboards!=null)leaderboards.refresh();
            sender.sendMessage("§aWiped "+targets.size()+
                " player(s). Offline inventories and Ender Chests will clear on next login.");
            getLogger().warning(sender.getName()+" executed /wipe all for "+targets.size()+" players.");
            return true;
        }
        if(args.length!=1){
            sender.sendMessage("§cUsage: /wipe <player>");return true;
        }
        OfflinePlayer target=null;
        for(Player online:Bukkit.getOnlinePlayers()){
            if(online.getName().equalsIgnoreCase(args[0])){target=online;break;}
        }
        if(target==null){
            for(OfflinePlayer known:Bukkit.getOfflinePlayers()){
                if(known.getName()!=null&&known.getName().equalsIgnoreCase(args[0])){
                    target=known;break;
                }
            }
        }
        if(target==null){
            sender.sendMessage("§cUnknown player. They must have joined the server before.");return true;
        }
        wipePlayerData(target.getUniqueId());
        save();
        if(leaderboards!=null)leaderboards.refresh();
        sender.sendMessage("§aWiped "+target.getName()+
            " (inventory, Ender Chest, homes, money, shards, hearts, playtime).");
        getLogger().warning(sender.getName()+" wiped "+target.getName()+
            " ("+target.getUniqueId()+")");
        return true;
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        String cmd=command.getName().toLowerCase(Locale.ROOT);
        if(cmd.equals("echest")){
            if(!(sender instanceof Player player)){
                sender.sendMessage("§cUse this command in-game.");return true;
            }
            if(args.length==0){player.openInventory(player.getEnderChest());return true;}
            if(args.length==1&&player.hasPermission("diddyblud.admin")){
                Player target=Bukkit.getPlayerExact(args[0]);
                if(target==null){player.sendMessage("§cPlayer must be online.");return true;}
                player.openInventory(target.getEnderChest());return true;
            }
            player.sendMessage("§eUsage: /echest [online player (OP only)]");return true;
        }
        if(cmd.equals("invsee")){
            if(!(sender instanceof Player player)){
                sender.sendMessage("§cUse this command in-game.");return true;
            }
            if(!player.hasPermission("diddyblud.admin")){
                player.sendMessage("§cOnly OPs can use /invsee.");return true;
            }
            if(args.length!=1){player.sendMessage("§eUsage: /invsee <player>");return true;}
            Player target=Bukkit.getPlayerExact(args[0]);
            if(target==null||target.equals(player)){
                player.sendMessage("§cChoose another online player.");return true;
            }
            openInventorySee(player,target);return true;
        }
        if(cmd.equals("unwipe"))return unwipeCommand(sender,args);
        if(cmd.equals("wipe"))return wipeCommand(sender,args);
        if(cmd.equals("rank"))return rankCommand(sender,args);
        if(cmd.equals("kits")||cmd.equals("kit")){
            if(!(sender instanceof Player player)){
                sender.sendMessage("§cUse /kits in-game.");return true;
            }
            openKits(player,0);return true;
        }
        if(cmd.equals("leaderboard")||cmd.equals("leaderboards")){
            if(!(sender instanceof Player player)){sender.sendMessage("Use this command in-game.");return true;}
            if(!player.hasPermission("diddyblud.admin")){
                player.sendMessage("§cOnly OPs can place leaderboards.");return true;
            }
            if(args.length<1||args.length>2||
                !(args[0].equalsIgnoreCase("money")||args[0].equalsIgnoreCase("playtime")||
                  args[0].equalsIgnoreCase("hearts"))||
                (args.length==2&&!args[1].equalsIgnoreCase("remove"))){
                player.sendMessage("§eUsage: /leaderboard <money|playtime|hearts> [remove]");
                return true;
            }
            String type=args[0].toLowerCase(Locale.ROOT);
            try{
                if(leaderboards==null){
                    player.sendMessage("§cLeaderboards are not initialized. Check startup console errors.");
                }else if(args.length==2){
                    player.sendMessage(leaderboards.remove(type)?"§aLeaderboard removed.":"§cNo placed leaderboard of that type.");
                }else{
                    leaderboards.place(player,type);
                }
            }catch(Exception ex){
                getLogger().warning("Leaderboard "+type+" failed for "+player.getName()+": "+ex);
                player.sendMessage("§cLeaderboard error: "+ex.getClass().getSimpleName()+" — check console.");
            }
            return true;
        }
        if(cmd.equals("sidebar")||cmd.equals("scoreboard")){
            if(!(sender instanceof Player player)){sender.sendMessage("Only players have a sidebar.");return true;}
            try{
                initializeSidebar(player);
                if(!sidebars.containsKey(player.getUniqueId())){
                    player.sendMessage("§cCannot show sidebar: scoreboard manager unavailable.");
                }else{
                    player.sendMessage("§aDiddyblud sidebar restored.");
                }
            }catch(Exception ex){
                getLogger().warning("Sidebar failed for "+player.getName()+": "+ex.getMessage());
                player.sendMessage("§cSidebar error: "+ex.getClass().getSimpleName()+" — check server console.");
            }
            return true;
        }
        if(cmd.equals("diddyblud")){
            sender.sendMessage("§6Diddyblud Lifesteal §fPaper §a"+getDescription().getVersion());
            sender.sendMessage("§7Features: Heart right-click, Halloween GUIs, scoreboard, and shard rewards.");
            return true;
        }
        if(!(sender instanceof Player p)){sender.sendMessage("In-game command required.");return true;}
        UUID uid=p.getUniqueId();
        try{
            switch(cmd){
                case "bal","balance" ->{
                    if(args.length==0){
                        p.sendMessage("§aYour balance: $"+funds(uid));return true;
                    }
                    if(args.length!=1){
                        p.sendMessage("§eUsage: /bal [player]");return true;
                    }
                    OfflinePlayer target=null;
                    Player online=Bukkit.getPlayerExact(args[0]);
                    if(online!=null)target=online;
                    if(target==null)for(OfflinePlayer known:Bukkit.getOfflinePlayers()){
                        if(known.getName()!=null&&known.getName().equalsIgnoreCase(args[0])){
                            target=known;break;
                        }
                    }
                    if(target==null){
                        p.sendMessage("§cPlayer not found. They must have joined before.");return true;
                    }
                    p.sendMessage("§a"+target.getName()+"'s balance: $"+funds(target.getUniqueId()));
                    return true;
                }
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
                case "shopedit" ->{
                    if(!p.hasPermission("diddyblud.admin"))return true;
                    if(args.length==2&&args[0].equalsIgnoreCase("deleteitem")){
                        String key=args[1].toUpperCase(Locale.ROOT);
                        if(!assignments.containsKey(key)){p.sendMessage("§cItem not found.");return true;}
                        deletedShopListings.add(key);assignments.remove(key);buy.remove(key);sell.remove(key);
                        itemOrder.remove(key);shopNames.remove("item:"+key);save();
                        p.sendMessage("§aDeleted listing "+key);return true;
                    }
                    p.sendMessage("§eUse /shop → Admin Mode: shift-left to swap; shift-right to rename.");
                    p.sendMessage("§e/shopedit deleteitem <item id> removes a listing.");return true;
                }
                case "sell" ->{
                    if(args.length==3&&args[0].equalsIgnoreCase("price")&&p.hasPermission("diddyblud.admin")){
                        String id=id(p.getInventory().getItemInMainHand());
                        if(id==null){p.sendMessage("Hold the item.");return true;}
                        if(id.equals(CLICK_VILLAGER_SHOP_ID)){
                            clickVillagerShopTemplate=p.getInventory().getItemInMainHand().clone();
                            clickVillagerShopTemplate.setAmount(1);
                            args[2]="0";
                            p.sendMessage("§eCaptured villagers are buy-only. Sell price fixed at $0.");
                        }
                        deletedShopListings.remove(id);
                        buy.put(id,parseAmount(args[1]));sell.put(id,parseAmount(args[2]));
                        assignments.putIfAbsent(id,"storage");save();p.sendMessage("Saved buy/sell for "+id);return true;
                    }
                    openSell(p);return true;
                }
                case "customitems" ->{if(p.hasPermission("diddyblud.admin"))openCustom(p);return true;}
                case "infiniterocket" ->{
                    if(!p.hasPermission("diddyblud.admin"))return true;
                    give(p,infiniteRocket());p.sendMessage("§aGiven an Infinite Rocket.");return true;
                }
                case "infinitewater" ->{
                    if(!p.hasPermission("diddyblud.admin"))return true;
                    give(p,infiniteWater());p.sendMessage("§aGiven an Infinite Water Bucket.");return true;
                }
                case "3x3pickaxe" ->{
                    if(!p.hasPermission("diddyblud.admin")){p.sendMessage("§cOP only.");return true;}
                    give(p,infiniteAreaPickaxe());
                    p.sendMessage("§aGiven an Infinite 3x3 Pickaxe.");
                    return true;
                }
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
                case "rtp" ->{
                    if(args.length==0){openRtp(p);return true;}
                    World.Environment env=switch(args[0].toLowerCase(Locale.ROOT)){
                        case "overworld" -> World.Environment.NORMAL;
                        case "nether" -> World.Environment.NETHER;
                        case "end" -> World.Environment.THE_END;
                        default -> null;
                    };
                    if(env==null)p.sendMessage("§eUsage: /rtp [overworld|nether|end]");
                    else teleportRandom(p,env);
                    return true;
                }
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
