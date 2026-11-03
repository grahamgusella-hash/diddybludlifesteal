package com.example.diddybludpaper;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Logs successful /pay transfers received by players running an auction.
 * These are actual payments, not reversible bids; existing /ah listings are separate.
 */
final class PlayerAuctionLog {
    private final DiddybludPaper plugin;
    private final File file;
    private final Map<UUID, Session> active = new HashMap<>();
    private final Map<UUID, List<Session>> history = new HashMap<>();

    private static final class Bid {
        UUID sender;
        String senderName;
        long amount, time;
        Bid(UUID sender, String senderName, long amount, long time) {
            this.sender=sender;this.senderName=senderName;this.amount=amount;this.time=time;
        }
    }
    private static final class Session {
        String title;
        long started, ended;
        final List<Bid> payments = new ArrayList<>();
        Session(String title,long started){this.title=title;this.started=started;}
    }

    PlayerAuctionLog(DiddybludPaper plugin) {
        this.plugin=plugin;
        file=new File(plugin.getDataFolder(),"player-auction-logs.yml");
        load();
    }

    boolean handle(Player player,String[] args) {
        UUID id=player.getUniqueId();
        if(args.length==0){
            help(player);return true;
        }
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "start" -> {
                if(active.containsKey(id)) {
                    player.sendMessage("§cYou already have an active auction. Use /auctionhost stop first.");
                    return true;
                }
                if(args.length<2){
                    player.sendMessage("§eUsage: /auctionhost start <item or auction title>");
                    return true;
                }
                String title=String.join(" ",Arrays.copyOfRange(args,1,args.length)).trim();
                if(title.length()>64){player.sendMessage("§cAuction title must be 64 characters or less.");return true;}
                active.put(id,new Session(title,System.currentTimeMillis()));
                save();
                player.sendMessage("§aAuction started: §f"+title);
                player.sendMessage("§7Successful /pay transfers to you will be logged until /auctionhost stop.");
                player.sendMessage("§eNote: payments transfer money immediately; ending an auction does not refund bids.");
                return true;
            }
            case "stop" -> {
                Session session=active.remove(id);
                if(session==null){player.sendMessage("§cYou have no active auction.");return true;}
                session.ended=System.currentTimeMillis();
                List<Session> sessions=history.computeIfAbsent(id,k->new ArrayList<>());
                sessions.add(session);
                while(sessions.size()>20)sessions.remove(0);
                save();
                player.sendMessage("§aAuction ended: §f"+session.title+"§a. Payments logged: §f"+session.payments.size());
                show(player,session);
                return true;
            }
            case "log","bids","status" -> {
                Session session=active.get(id);
                if(session==null){
                    List<Session> sessions=history.getOrDefault(id,List.of());
                    if(sessions.isEmpty()){player.sendMessage("§eNo auction logs yet.");return true;}
                    session=sessions.get(sessions.size()-1);
                }
                show(player,session);
                return true;
            }
            default -> {help(player);return true;}
        }
    }
    private void help(Player player) {
        player.sendMessage("§6Auction tracker: §e/auctionhost start <title>§7, §e/auctionhost log§7, §e/auctionhost stop");
        player.sendMessage("§7Every successful /pay to you while active is saved with sender, amount and time.");
    }
    private static String money(long amount) {return String.format(Locale.US,"%,d",amount);}

    private void show(Player player,Session session) {
        player.sendMessage("§6Auction: §f"+session.title+" §7("+ (session.ended==0?"ACTIVE":"ended") +")");
        if(session.payments.isEmpty()){player.sendMessage("§7No payments logged.");return;}
        long total=0; Map<UUID,Long> byPlayer=new LinkedHashMap<>();
        Map<UUID,String> names=new HashMap<>();
        for(Bid bid:session.payments){
            if(Long.MAX_VALUE-total>=bid.amount)total+=bid.amount;
            byPlayer.merge(bid.sender,bid.amount,(a,b)->Long.MAX_VALUE-a<b?Long.MAX_VALUE:a+b);
            names.put(bid.sender,bid.senderName);
        }
        player.sendMessage("§ePayments: §f"+session.payments.size()+" §eTotal received: §f$"+money(total));
        List<Map.Entry<UUID,Long>> sorted=new ArrayList<>(byPlayer.entrySet());
        sorted.sort((a,b)->Long.compare(b.getValue(),a.getValue()));
        for(int i=0;i<Math.min(8,sorted.size());i++){
            var entry=sorted.get(i);
            player.sendMessage("§7"+(i+1)+". §f"+names.get(entry.getKey())+" §a$"+money(entry.getValue())+" §7(total payments)");
        }
    }

    void onSuccessfulPay(Player sender, UUID receiverId, long amount) {
        Session session=active.get(receiverId);
        if(session==null || amount<=0)return;
        session.payments.add(new Bid(sender.getUniqueId(),sender.getName(),amount,System.currentTimeMillis()));
        save();
        Player receiver=Bukkit.getPlayer(receiverId);
        if(receiver!=null){
            receiver.sendMessage("§6[Auction] §f"+sender.getName()+" §apaid $"+money(amount)+
                " §7("+session.payments.size()+" payments logged)");
        }
    }

    private void save() {
        YamlConfiguration config=new YamlConfiguration();
        Set<UUID> owners=new HashSet<>(history.keySet());
        owners.addAll(active.keySet());
        for(UUID owner:owners){
            List<Session> sessions=new ArrayList<>(history.getOrDefault(owner,List.of()));
            Session current=active.get(owner);
            if(current!=null)sessions.add(current);
            int i=0;
            for(Session session:sessions){
                String path="owners."+owner+".sessions."+i++;
                config.set(path+".title",session.title);
                config.set(path+".started",session.started);
                config.set(path+".ended",session.ended);
                for(int j=0;j<session.payments.size();j++){
                    Bid bid=session.payments.get(j);
                    String item=path+".payments."+j;
                    config.set(item+".sender",bid.sender.toString());
                    config.set(item+".name",bid.senderName);
                    config.set(item+".amount",bid.amount);
                    config.set(item+".time",bid.time);
                }
            }
        }
        try {config.save(file);}
        catch(IOException e){plugin.getLogger().warning("Unable to save auction logs: "+e.getMessage());}
    }
    private void load() {
        YamlConfiguration config=YamlConfiguration.loadConfiguration(file);
        ConfigurationSection owners=config.getConfigurationSection("owners");
        if(owners==null)return;
        for(String rawOwner:owners.getKeys(false)){
            UUID owner;
            try{owner=UUID.fromString(rawOwner);}
            catch(IllegalArgumentException e){continue;}
            ConfigurationSection sessions=config.getConfigurationSection("owners."+rawOwner+".sessions");
            if(sessions==null)continue;
            for(String index:sessions.getKeys(false)){
                String path="owners."+rawOwner+".sessions."+index;
                Session session=new Session(config.getString(path+".title","Auction"),config.getLong(path+".started"));
                session.ended=config.getLong(path+".ended");
                ConfigurationSection payments=config.getConfigurationSection(path+".payments");
                if(payments!=null)for(String bidIndex:payments.getKeys(false)){
                    String entry=path+".payments."+bidIndex;
                    try{
                        session.payments.add(new Bid(UUID.fromString(config.getString(entry+".sender")),
                            config.getString(entry+".name","Unknown"),
                            config.getLong(entry+".amount"),config.getLong(entry+".time")));
                    }catch(IllegalArgumentException ignored){}
                }
                if(session.ended==0 && !active.containsKey(owner))active.put(owner,session);
                else history.computeIfAbsent(owner,k->new ArrayList<>()).add(session);
            }
        }
    }
}
