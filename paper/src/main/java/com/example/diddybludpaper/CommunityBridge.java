package com.example.diddybludpaper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Secure outbound-only bridge. All Bukkit API operations occur on the server main thread. */
final class CommunityBridge implements Listener {
    private final DiddybludPaper plugin;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ConcurrentLinkedQueue<String> chat=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> acknowledgments=new ConcurrentLinkedQueue<>();
    private final AtomicBoolean inFlight=new AtomicBoolean(false);
    private final String url,secret;
    private BukkitTask timer;
    private volatile boolean running=true;

    CommunityBridge(DiddybludPaper plugin){
        this.plugin=plugin;
        this.url=plugin.getConfig().getString("community-bridge.url","").strip().replaceAll("/+$","");
        this.secret=plugin.getConfig().getString("community-bridge.secret","");
    }
    void start(){
        if(!plugin.getConfig().getBoolean("community-bridge.enabled",false)){
            plugin.getLogger().info("Community bridge disabled. Configure community-bridge in config.yml to enable.");
            return;
        }
        if(!url.startsWith("https://")||secret.length()<24){
            plugin.getLogger().warning("Community bridge requires an HTTPS URL and secret of at least 24 characters.");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this,plugin);
        timer=Bukkit.getScheduler().runTaskTimer(plugin,this::poll,60L,200L);
        plugin.getLogger().info("Community bridge enabled (sync every 10 seconds).");
    }
    void stop(){running=false;if(timer!=null)timer.cancel();}

    @EventHandler(ignoreCancelled=true)
    public void onChat(AsyncPlayerChatEvent event){
        if(!running||event.getMessage().isBlank())return;
        if(chat.size()>150)return;
        String message=safe(event.getPlayer().getName(),16)+": "+safe(event.getMessage(),180);
        chat.offer(message);
    }
    private static String safe(String text,int limit){
        String value=text==null?"":text.replaceAll("[\\p{Cntrl}\\r\\n]"," ").replace("@","＠");
        return value.length()>limit?value.substring(0,limit):value;
    }
    private void poll(){
        if(!running||!inFlight.compareAndSet(false,true))return;
        // Snapshot player values while on Bukkit's main thread.
        JsonObject payload=new JsonObject();
        JsonArray players=new JsonArray();
        for(Player p:Bukkit.getOnlinePlayers()){
            JsonObject player=new JsonObject();
            player.addProperty("name",p.getName());
            player.addProperty("money",plugin.funds(p.getUniqueId()));
            player.addProperty("shards",plugin.bridgeShards(p.getUniqueId()));
            player.addProperty("playtime",plugin.bridgePlaytime(p.getUniqueId()));
            player.addProperty("hearts",plugin.bridgeHearts(p));
            player.addProperty("rank",plugin.bridgeRank(p.getUniqueId()));
            player.addProperty("levels",plugin.bridgeLevelBalance(p.getUniqueId()));
            players.add(player);
        }
        payload.add("players",players);
        JsonObject leaderboards=new JsonObject();
        for(String category:List.of("money","playtime","hearts")){
            JsonArray results=new JsonArray();
            for(Map<String,Object> entry:plugin.bridgeLeaderboard(category)){
                JsonObject result=new JsonObject();
                result.addProperty("name",String.valueOf(entry.get("name")));
                result.addProperty("value",(Long)entry.get("value"));
                results.add(result);
            }
            leaderboards.add(category,results);
        }
        payload.add("leaderboards",leaderboards);
        JsonArray applicationRoles=new JsonArray();
        for(Map<String,String> role:plugin.bridgeApplicationRoles()){
            JsonObject item=new JsonObject();
            item.addProperty("id",role.get("id"));
            item.addProperty("name",role.get("name"));
            applicationRoles.add(item);
        }
        payload.add("applicationRoles",applicationRoles);
        LevelSystem.JsonShop shop=plugin.bridgeLevelShop();
        if(shop!=null){
            JsonObject catalog=new JsonObject();
            catalog.addProperty("playtimeMinutes",shop.playtimeMinutes());
            catalog.addProperty("moneyPerLevel",shop.moneyPerLevel());
            JsonArray offers=new JsonArray();
            for(Map<String,Object> o:shop.offers()){
                JsonObject item=new JsonObject();
                item.addProperty("id",String.valueOf(o.get("id")));
                item.addProperty("name",String.valueOf(o.get("name")));
                item.addProperty("type",String.valueOf(o.get("type")));
                item.addProperty("cost",(Integer)o.get("cost"));
                offers.add(item);
            }
            catalog.add("offers",offers);
            payload.add("levelShop",catalog);
        }
        JsonArray sentChat=new JsonArray();
        List<String> batch=new ArrayList<>();
        for(int n=0;n<20;n++){String line=chat.poll();if(line==null)break;batch.add(line);sentChat.add(line);}
        payload.add("chat",sentChat);
        JsonArray ackBatch=new JsonArray();
        List<String> sentAcks=new ArrayList<>();
        for(int n=0;n<60;n++){String id=acknowledgments.poll();if(id==null)break;sentAcks.add(id);ackBatch.add(id);}
        payload.add("acks",ackBatch);
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try{
                HttpRequest req=HttpRequest.newBuilder(URI.create(url+"/api/server/sync"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization","Bearer "+secret)
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
                HttpResponse<String> res=client.send(req,HttpResponse.BodyHandlers.ofString());
                if(res.statusCode()!=200)throw new IllegalStateException("HTTP "+res.statusCode());
                JsonObject parsed=JsonParser.parseString(res.body()).getAsJsonObject();
                JsonArray actions=parsed.getAsJsonArray("commands");
                if(running&&actions!=null)Bukkit.getScheduler().runTask(plugin,()->process(actions));
            }catch(Exception ex){
                // Restore unsent chat; no other Bukkit operations from this async thread.
                for(String line:batch)if(chat.size()<150)chat.offer(line);
                for(String id:sentAcks)acknowledgments.offer(id);
                plugin.getLogger().warning("Community bridge sync failed: "+ex.getMessage());
            }finally{inFlight.set(false);}
        });
    }
    private static String str(JsonObject obj,String key,int max){
        JsonElement v=obj.get(key);
        if(v==null||!v.isJsonPrimitive())return "";
        return safe(v.getAsString(),max);
    }
    private void process(JsonArray actions){
        if(!running)return;
        for(JsonElement e:actions){
            if(!e.isJsonObject())continue;
            JsonObject cmd=e.getAsJsonObject();
            String messageId=str(cmd,"id",36);
            if(messageId.matches("[0-9a-f-]{36}"))acknowledgments.offer(messageId);
            String type=str(cmd,"type",30);
            switch(type){
                case "chat" -> Bukkit.broadcastMessage("§7"+str(cmd,"text",250));
                case "announce" -> Bukkit.broadcastMessage("§c[Announcement] §f"+str(cmd,"text",250));
                case "kick" -> {
                    String name=str(cmd,"player",16);
                    if(!name.matches("[A-Za-z0-9_]{3,16}"))break;
                    Player target=Bukkit.getPlayerExact(name);
                    if(target!=null)target.kickPlayer(str(cmd,"reason",100));
                }
                case "level_purchase" -> {
                    String name=str(cmd,"player",16),code=str(cmd,"code",20),offer=str(cmd,"offer",32);
                    if(name.matches("[A-Za-z0-9_]{3,16}")&&code.matches("[A-Z2-9]{10}")&&offer.matches("[a-z0-9_-]{1,32}"))
                        plugin.bridgeRedeemLevels(name,code,offer);
                }
                case "rank" -> {
                    String name=str(cmd,"player",16),rank=str(cmd,"rank",40);
                    if(!name.matches("[A-Za-z0-9_]{3,16}")||!rank.matches("[a-z0-9_-]{1,40}"))break;
                    // No automatic grants by default: the website form is not payment or identity verification.
                    if(!plugin.getConfig().getBoolean("community-bridge.allow-staff-approved-ranks",false)){
                        plugin.getLogger().info("Rank action requires manual verification: "+name+" / "+rank);
                        break;
                    }
                    Player target=Bukkit.getPlayerExact(name);
                    if(target!=null){
                        if(!plugin.bridgeGrantRank(target,rank))
                            plugin.getLogger().warning("Bridge rank is not defined: "+rank);
                    }else plugin.getLogger().warning("Bridge rank target not online: "+name);
                }
                default -> plugin.getLogger().warning("Ignored unsupported bridge action type: "+type);
            }
        }
    }
}
