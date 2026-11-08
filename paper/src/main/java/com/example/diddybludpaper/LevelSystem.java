package com.example.diddybludpaper;

import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Spendable, server-side levels. Money conversion is an explicit purchase (/levels buy),
 * not an automatic debit. Online playtime awards a configurable number of levels per interval.
 * Website redemptions require a short-lived code generated inside Minecraft.
 */
final class LevelSystem {
    private static final String ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final DiddybludPaper plugin;
    private final File file;
    private final Map<UUID,Long> levels=new HashMap<>(),seconds=new HashMap<>();
    private final Map<UUID,Code> codes=new HashMap<>();
    private final SecureRandom random=new SecureRandom();
    private record Code(String text,long expires){}
    private record Offer(String name,String type,String value,int cost){}
    private final Map<String,Offer> offers=new LinkedHashMap<>();
    private int minutesPerLevel;
    private int levelsPerInterval;
    private long dollarsPerLevel;

    LevelSystem(DiddybludPaper plugin){
        this.plugin=plugin;
        file=new File(plugin.getDataFolder(),"levels.yml");
        migrateOldDefaults();
        reload();
        load();
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1200L,1200L);
        Bukkit.getScheduler().runTaskTimer(plugin,this::save,6000L,6000L);
    }
    private void migrateOldDefaults(){
        // Existing installations keep their old config.yml on plugin updates.
        // Update the previous shipped defaults once, preserving customized rates.
        if(plugin.getConfig().getBoolean("leveling.rates-2026-updated",false))return;
        if(plugin.getConfig().getLong("leveling.money-per-level",10_000_000L)==10_000_000L)
            plugin.getConfig().set("leveling.money-per-level",1_000_000L);
        plugin.getConfig().set("leveling.levels-per-playtime-interval",50);
        plugin.getConfig().set("leveling.rates-2026-updated",true);
        plugin.saveConfig();
    }
    void reload(){
        minutesPerLevel=Math.max(1,plugin.getConfig().getInt("leveling.playtime-minutes-per-level",30));
        levelsPerInterval=Math.max(1,Math.min(10000,plugin.getConfig().getInt("leveling.levels-per-playtime-interval",50)));
        dollarsPerLevel=Math.max(1L,plugin.getConfig().getLong("leveling.money-per-level",1000000L));
        offers.clear();
        ConfigurationSection section=plugin.getConfig().getConfigurationSection("leveling.offers");
        if(section!=null)for(String id:section.getKeys(false)){
            if(!id.matches("[a-z0-9_-]{1,32}"))continue;
            ConfigurationSection entry=section.getConfigurationSection(id);
            if(entry==null)continue;
            String type=entry.getString("type","").toLowerCase(Locale.ROOT);
            String value=entry.getString("value","");
            int cost=entry.getInt("cost",0);
            if(!Set.of("rank","money","shards").contains(type)||cost<=0||cost>1000000)continue;
            if(type.equals("rank")&&!value.matches("[a-z0-9_-]{1,40}"))continue;
            if(!type.equals("rank"))try{
                long reward=Long.parseLong(value);
                if(reward<=0||reward>1000000000000L)continue;
            }catch(NumberFormatException ex){continue;}
            offers.put(id,new Offer(entry.getString("name",id),type,value,cost));
        }
    }
    private void load(){
        if(!file.exists())return;
        YamlConfiguration y=YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all=y.getConfigurationSection("players");
        if(all==null)return;
        for(String key:all.getKeys(false))try{
            UUID id=UUID.fromString(key);
            levels.put(id,Math.max(0,y.getLong("players."+key+".levels",0)));
            seconds.put(id,Math.max(0,y.getLong("players."+key+".seconds",0)));
        }catch(IllegalArgumentException ignored){}
    }
    void save(){
        YamlConfiguration y=new YamlConfiguration();
        Set<UUID> all=new HashSet<>(levels.keySet());all.addAll(seconds.keySet());
        for(UUID id:all){
            String base="players."+id;
            y.set(base+".levels",levels.getOrDefault(id,0L));
            y.set(base+".seconds",seconds.getOrDefault(id,0L));
        }
        try{y.save(file);}catch(IOException ex){plugin.getLogger().warning("Could not save levels.yml: "+ex.getMessage());}
    }
    private void tick(){
        long threshold=minutesPerLevel*60L;
        for(Player p:Bukkit.getOnlinePlayers()){
            UUID id=p.getUniqueId();
            long next=seconds.getOrDefault(id,0L)+60L;
            if(next>=threshold){
                long earned=(next/threshold)*levelsPerInterval;
                next%=threshold;
                long prior=levels.getOrDefault(id,0L);
                levels.put(id,Math.min(1000000000L,prior+earned));
                p.sendMessage("§a+"+earned+" levels from playtime! §e/levels §7to see your balance.");
            }
            seconds.put(id,next);
        }
    }
    long balance(UUID id){return levels.getOrDefault(id,0L);}
    long moneyCost(){return dollarsPerLevel;}
    JsonShop catalog(){
        List<Map<String,Object>> entries=new ArrayList<>();
        for(var item:offers.entrySet()){
            Offer offer=item.getValue();
            entries.add(Map.of("id",item.getKey(),"name",offer.name(),"type",offer.type(),"cost",offer.cost()));
        }
        return new JsonShop(entries,minutesPerLevel,dollarsPerLevel,levelsPerInterval);
    }
    record JsonShop(List<Map<String,Object>> offers,int playtimeMinutes,long moneyPerLevel,int levelsPerInterval){}
    boolean command(CommandSender sender,String[] args){
        if(!(sender instanceof Player player)){sender.sendMessage("Use /levels in-game.");return true;}
        UUID id=player.getUniqueId();
        if(args.length==0){
            player.sendMessage("§dLevels: §f"+balance(id)+" §7| Playtime: +"+levelsPerInterval+" levels every "+minutesPerLevel+" minutes");
            player.sendMessage("§7Money conversion: $"+dollarsPerLevel+" for 1 level using §e/levels buy [amount]");
            player.sendMessage("§7Website purchase code: §e/levels code");
            return true;
        }
        if(args[0].equalsIgnoreCase("buy")){
            int amount=1;
            try{if(args.length>1)amount=Integer.parseInt(args[1]);}catch(NumberFormatException ex){amount=0;}
            if(amount<1||amount>1000||dollarsPerLevel>Long.MAX_VALUE/amount){
                player.sendMessage("§cChoose between 1 and 1000 levels.");return true;
            }
            long total=dollarsPerLevel*amount;
            if(balance(id)>1000000000L-amount){player.sendMessage("§cLevel balance limit reached.");return true;}
            if(!plugin.charge(id,total)){
                player.sendMessage("§cYou need $"+total+" in-game for "+amount+" levels.");return true;
            }
            levels.put(id,balance(id)+amount);save();
            player.sendMessage("§aBought "+amount+" level(s) for $"+total+". New balance: "+balance(id));
            return true;
        }
        if(args[0].equalsIgnoreCase("code")){
            byte[] bytes=new byte[10];random.nextBytes(bytes);
            StringBuilder b=new StringBuilder();
            for(int i=0;i<10;i++)b.append(ALPHABET.charAt((bytes[i]&255)%ALPHABET.length()));
            String code=b.toString();
            codes.put(id,new Code(code,System.currentTimeMillis()+5*60*1000));
            Component clickableCode=Component.text(code,NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.copyToClipboard(code))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy this website code",NamedTextColor.GREEN)));
            player.sendMessage(Component.text("Website level-shop code: ",NamedTextColor.GREEN)
                .append(clickableCode)
                .append(Component.text(" (click to copy, expires in 5 minutes)",NamedTextColor.GRAY)));
            player.sendMessage("§7Never give this code to another player.");
            return true;
        }
        player.sendMessage("§e/levels §7| §e/levels buy [amount] §7| §e/levels code");return true;
    }
    /** OP-only command handlers; persisted in the server's private config.yml. */
    boolean setMoneyPerLevel(long amount){
        if(amount<=0||amount>1_000_000_000_000_000L)return false;
        plugin.getConfig().set("leveling.money-per-level",amount);
        plugin.saveConfig();
        reload();
        return true;
    }
    boolean makeRankBuyable(String rank,int cost){
        if(!rank.matches("[a-z0-9_-]{1,24}")||cost<1||cost>1_000_000||!plugin.bridgeRankExists(rank))return false;
        String key="leveling.offers."+rank;
        plugin.getConfig().set(key+".name",plugin.bridgeRankName(rank)+" Rank");
        plugin.getConfig().set(key+".type","rank");
        plugin.getConfig().set(key+".value",rank);
        plugin.getConfig().set(key+".cost",cost);
        plugin.saveConfig();
        reload();
        return true;
    }
    boolean removeBuyableRank(String rank){
        if(!rank.matches("[a-z0-9_-]{1,24}"))return false;
        String key="leveling.offers."+rank;
        if(!plugin.getConfig().getString(key+".type","").equalsIgnoreCase("rank"))return false;
        plugin.getConfig().set(key,null);
        plugin.saveConfig();
        reload();
        return true;
    }
    /** Called from the primary server thread only. */
    void redeem(String name,String code,String offerId){
        Player target=Bukkit.getPlayerExact(name);
        if(target==null){plugin.getLogger().info("Level purchase skipped: player offline ("+name+").");return;}
        UUID id=target.getUniqueId();
        Code pending=codes.get(id);
        if(pending==null||pending.expires()<System.currentTimeMillis()||!pending.text().equals(code)){
            target.sendMessage("§cWebsite level purchase rejected: code expired or invalid.");return;
        }
        Offer offer=offers.get(offerId);
        if(offer==null){target.sendMessage("§cWebsite reward is no longer available.");return;}
        if(balance(id)<offer.cost()){
            target.sendMessage("§cYou need "+offer.cost()+" levels for "+offer.name()+".");return;
        }
        if(offer.type().equals("rank")&&!plugin.bridgeRankExists(offer.value())){
            target.sendMessage("§cThis rank has not been created by staff yet.");return;
        }
        long quantity=0;
        if(!offer.type().equals("rank")){
            try{quantity=Long.parseLong(offer.value());}catch(NumberFormatException ex){return;}
            if(offer.type().equals("money")&&plugin.funds(id)>Long.MAX_VALUE-quantity)return;
            if(offer.type().equals("shards")&&!plugin.bridgeCanAddShards(id,quantity))return;
        }
        // Consume the claim code once, only after all validation passes.
        codes.remove(id);
        levels.put(id,balance(id)-offer.cost());
        boolean granted=switch(offer.type()){
            case "rank" -> plugin.bridgeGrantRank(target,offer.value());
            case "money" -> plugin.credit(id,quantity);
            case "shards" -> plugin.bridgeAddShards(id,quantity);
            default -> false;
        };
        if(!granted){
            levels.put(id,balance(id)+offer.cost());
            plugin.getLogger().warning("Failed to grant level-shop purchase "+offerId+" to "+name);
            target.sendMessage("§cPurchase failed; your levels were returned.");return;
        }
        save();
        target.sendMessage("§aPurchased "+offer.name()+" for "+offer.cost()+" levels. Remaining: "+balance(id));
        plugin.getLogger().info("Level shop purchase: "+name+" bought "+offerId+" for "+offer.cost()+" levels.");
    }
}
