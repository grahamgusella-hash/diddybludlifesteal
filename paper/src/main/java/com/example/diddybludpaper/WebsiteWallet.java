package com.example.diddybludpaper;

import com.google.gson.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class WebsiteWallet {
    private final DiddybludPaper plugin;
    private final String url,secret;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final Map<UUID,JsonObject> results=new LinkedHashMap<>();
    private final Map<UUID,Long> lastLink=new HashMap<>();
    private BukkitTask timer;
    private boolean enabled=false;
    WebsiteWallet(DiddybludPaper plugin){
        this.plugin=plugin;
        url=plugin.getConfig().getString("community-bridge.url","").strip().replaceAll("/+$","");
        secret=plugin.getConfig().getString("community-bridge.secret","");
    }
    void start(){
        enabled=plugin.getConfig().getBoolean("community-bridge.enabled",false)&&url.startsWith("https://")&&secret.length()>=24;
        if(enabled)timer=Bukkit.getScheduler().runTaskTimer(plugin,this::poll,40L,60L);
    }
    void stop(){enabled=false;if(timer!=null)timer.cancel();}
    private JsonObject request(String path,JsonObject body)throws Exception{
        var req=HttpRequest.newBuilder(URI.create(url+"/api/server/wallet/"+path)).timeout(Duration.ofSeconds(10))
            .header("Authorization","Bearer "+secret).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var res=client.send(req,HttpResponse.BodyHandlers.ofString());
        if(res.statusCode()!=200)throw new IllegalStateException("Wallet HTTP "+res.statusCode());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }
    void link(Player p){
        if(!enabled){p.sendMessage("§cWebsite connection isn't configured.");return;}
        UUID id=p.getUniqueId();long now=System.currentTimeMillis();
        if(now-lastLink.getOrDefault(id,0L)<5000){p.sendMessage("§ePlease wait before requesting another link.");return;}
        lastLink.put(id,now);
        JsonObject body=new JsonObject();body.addProperty("uuid",id.toString());body.addProperty("name",p.getName());
        p.sendMessage("§7Creating a private, one-time website login link...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try{
                String link=request("link",body).get("link").getAsString();
                if(!link.startsWith("https://"))throw new IllegalStateException("Invalid link");
                Bukkit.getScheduler().runTask(plugin,()->{
                    Player online=Bukkit.getPlayer(id);if(online==null)return;
                    online.sendMessage(Component.text("§a[Open Diddyblud website]").clickEvent(ClickEvent.openUrl(link)));
                    online.sendMessage(Component.text("§e[Copy private login link]").clickEvent(ClickEvent.copyToClipboard(link)));
                    online.sendMessage("§7Expires in 5 minutes. Never share this link.");
                });
            }catch(Exception ex){Bukkit.getScheduler().runTask(plugin,()->{Player online=Bukkit.getPlayer(id);if(online!=null)online.sendMessage("§cWebsite login unavailable. Try again later.");});}
        });
    }
    private void poll(){
        if(!enabled||!busy.compareAndSet(false,true))return;
        JsonObject body=new JsonObject();JsonArray sent=new JsonArray();
        results.values().stream().limit(100).forEach(sent::add);
        body.add("results",sent);
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try{
                JsonObject response=request("poll",body);
                Bukkit.getScheduler().runTask(plugin,()->{
                    try{
                        for(JsonElement e:response.getAsJsonArray("accepted"))results.remove(UUID.fromString(e.getAsString()));
                        for(JsonElement e:response.getAsJsonArray("jobs")){
                            try{
                                JsonObject j=e.getAsJsonObject();UUID id=UUID.fromString(j.get("id").getAsString()),owner=UUID.fromString(j.get("owner").getAsString());
                                if(results.containsKey(id))continue;
                                String kind=j.get("kind").getAsString();
                                JsonObject out;
                                if(kind.equals("balance")){out=plugin.websiteBalance(owner);out.addProperty("ok",true);out.addProperty("owner",owner.toString());}
                                else if(kind.equals("deposit")||kind.equals("withdraw"))out=plugin.websiteTransfer(owner,id,kind,Long.parseLong(j.get("amount").getAsString()));
                                else continue;
                                out.addProperty("id",id.toString());results.put(id,out);
                            }catch(Exception ex){plugin.getLogger().warning("Wallet operation not completed: "+ex.getMessage());}
                        }
                    }finally{busy.set(false);}
                });
            }catch(Exception ex){busy.set(false);}
        });
    }
}
