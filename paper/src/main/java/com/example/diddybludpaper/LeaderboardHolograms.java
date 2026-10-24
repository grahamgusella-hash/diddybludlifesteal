package com.example.diddybludpaper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Persistent locations, transient text entities; no client mods required. */
final class LeaderboardHolograms {
    private final JavaPlugin plugin;
    private final java.util.function.Function<String,Map<UUID,Long>> scores;
    private final Map<String,Location> locations=new LinkedHashMap<>();
    private final Map<String,TextDisplay> displays=new HashMap<>();
    private final File file;
    private final LegacyComponentSerializer legacy=LegacyComponentSerializer.legacySection();
    private static final Set<String> TYPES=Set.of("money","playtime","hearts");

    LeaderboardHolograms(JavaPlugin plugin,java.util.function.Function<String,Map<UUID,Long>> scores){
        this.plugin=plugin;this.scores=scores;
        this.file=new File(plugin.getDataFolder(),"leaderboards.yml");
        if(!file.exists())return;
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section=yaml.getConfigurationSection("locations");
        if(section!=null)for(String kind:section.getKeys(false)){
            if(TYPES.contains(kind)){
                Location location=section.getLocation(kind);
                if(location!=null)locations.put(kind,location);
            }
        }
    }

    void place(Player player,String kind){
        if(!TYPES.contains(kind))return;
        // Place above the OP's feet so the hologram is comfortably visible.
        Location location=player.getLocation().clone().add(0,2.6,0);
        location.setYaw(0);location.setPitch(0);
        removeEntity(kind);
        locations.put(kind,location);
        save();
        refresh();
        player.sendMessage("§aPlaced "+kind+" leaderboard hologram. Everyone nearby can see it.");
    }

    boolean remove(String kind){
        if(!TYPES.contains(kind)||locations.remove(kind)==null)return false;
        removeEntity(kind);save();return true;
    }

    void refresh(){
        for(var entry:locations.entrySet()){
            String kind=entry.getKey();
            Location loc=entry.getValue();
            World world=loc.getWorld();
            if(world==null||!world.isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4)){
                removeEntity(kind);
                continue;
            }
            TextDisplay entity=displays.get(kind);
            if(entity==null||!entity.isValid()||entity.getWorld()!=world){
                if(entity!=null&&entity.isValid())entity.remove();
                entity=world.spawn(loc,TextDisplay.class,display->{
                    display.setPersistent(false);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setDefaultBackground(false);
                    display.setSeeThrough(false);
                    display.setShadowed(true);
                    display.setLineWidth(350);
                });
                displays.put(kind,entity);
            }
            entity.text(legacy.deserialize(render(kind)));
        }
    }

    private String render(String kind){
        String heading=switch(kind){
            case "money"->"§6§lTOP MONEY";
            case "playtime"->"§b§lTOP PLAYTIME";
            default->"§c§lTOP HEARTS";
        };
        StringBuilder text=new StringBuilder("§8✦ §f§lDIDDYBLUD §8✦\n"+heading+"\n§8────────────");
        List<Map.Entry<UUID,Long>> list=scores.apply(kind).entrySet().stream()
            .filter(e->e.getValue()>0)
            .sorted(Comparator.<Map.Entry<UUID,Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(e->e.getKey().toString()))
            .limit(10).toList();
        if(list.isEmpty())text.append("\n§7No players ranked yet");
        int rank=1;
        for(var record:list){
            String name=Bukkit.getOfflinePlayer(record.getKey()).getName();
            if(name==null||name.isBlank())name=record.getKey().toString().substring(0,8);
            long value=record.getValue();
            String shown=kind.equals("playtime")?formatTime(value):
                kind.equals("money")?"$"+formatMoney(value):value+" ❤";
            text.append("\n§e#").append(rank++).append(" §f").append(name)
                .append(" §7- §a").append(shown);
        }
        text.append("\n§8────────────");
        return text.toString();
    }

    private static String formatMoney(long v){
        if(v>=1_000_000_000)return String.format(Locale.ROOT,"%.1fB",v/1_000_000_000.0);
        if(v>=1_000_000)return String.format(Locale.ROOT,"%.1fM",v/1_000_000.0);
        if(v>=10_000)return String.format(Locale.ROOT,"%.1fK",v/1_000.0);
        return Long.toString(v);
    }
    private static String formatTime(long seconds){
        long hours=seconds/3600;
        long minutes=(seconds%3600)/60;
        return hours+"h "+minutes+"m";
    }

    private void removeEntity(String kind){
        TextDisplay display=displays.remove(kind);
        if(display!=null&&display.isValid())display.remove();
    }

    void close(){
        for(String kind:new ArrayList<>(displays.keySet()))removeEntity(kind);
        save();
    }

    private void save(){
        YamlConfiguration yaml=new YamlConfiguration();
        locations.forEach((type,loc)->yaml.set("locations."+type,loc));
        try{
            file.getParentFile().mkdirs();
            yaml.save(file);
        }catch(IOException ex){
            plugin.getLogger().warning("Could not save leaderboard locations: "+ex.getMessage());
        }
    }
}
