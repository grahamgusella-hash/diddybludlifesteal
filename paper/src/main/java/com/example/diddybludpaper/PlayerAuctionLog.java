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
        long started, ended, endsAt, minimumBid;
        long highestBid;
        final List<Bid> payments = new ArrayList<>();
        Session(String title,long started){this.title=title;this.started=started;}
    }

    PlayerAuctionLog(DiddybludPaper plugin) {
        this.plugin=plugin;
        file=new File(plugin.getDataFolder(),"player-auction-logs.yml");
        load();
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::expire,20L,20L);
    }

    record PaymentView(String name,long amount,long time) {}
    record AuctionView(String title,boolean active,int count,long total,List<PaymentView> payments,long endsAt,long minimumBid,long highestBid) {}

    boolean isActive(UUID owner){expire();return active.containsKey(owner);}
    AuctionView view(UUID owner){
        expire();
        Session session=active.get(owner);
        if(session==null){
            List<Session> sessions=history.getOrDefault(owner,List.of());
            if(sessions.isEmpty())return null;
            session=sessions.get(sessions.size()-1);
        }
        List<PaymentView> entries=new ArrayList<>();
        long total=0;
        for(Bid bid:session.payments){
            entries.add(new PaymentView(bid.senderName,bid.amount,bid.time));
            total=Long.MAX_VALUE-total<bid.amount?Long.MAX_VALUE:total+bid.amount;
        }
        return new AuctionView(session.title,session.ended==0,entries.size(),total,List.copyOf(entries),session.endsAt,session.minimumBid,session.highestBid);
    }

    // Only successful server-side /pay transfers can become auction bids.
    boolean validateBid(Player sender,UUID recipient,long amount){
        expire();
        Session auction=active.get(recipient);
        if(auction==null)return true;
        if(amount<auction.minimumBid){
            sender.sendMessage("§cAuction minimum bid: $"+money(auction.minimumBid));
            return false;
        }
        if(amount<=auction.highestBid){
            sender.sendMessage("§cYour payment must exceed the current high bid of $"+money(auction.highestBid)+".");
            return false;
        }
        return true;
    }

    void expire(){
        long now=System.currentTimeMillis();
        List<UUID> expired=new ArrayList<>();
        for(var e:active.entrySet())if(e.getValue().endsAt>0&&e.getValue().endsAt<=now)expired.add(e.getKey());
        for(UUID owner:expired)close(owner,true);
    }
    private void close(UUID owner,boolean expired){
        Session session=active.remove(owner);
        if(session==null)return;
        session.ended=System.currentTimeMillis();
        List<Session> sessions=history.computeIfAbsent(owner,k->new ArrayList<>());
        sessions.add(session);
        while(sessions.size()>20)sessions.remove(0);
        save();
        Player player=Bukkit.getPlayer(owner);
        if(player!=null)player.sendMessage("§6[Auction] §e"+(expired?"Time expired":"Auction stopped")+" §7- "+session.title);
    }
    private static String enc(String s){
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    // A one-shot state reply consumed by the companion Fabric screen.
    private void sendState(Player player){
        AuctionView view=view(player.getUniqueId());
        if(view==null){
            player.sendMessage("DIDDY_AUC|none");return;
        }
        StringBuilder msg=new StringBuilder("DIDDY_AUC|");
        msg.append(view.active()?"active":"ended").append('|')
           .append(enc(view.title())).append('|')
           .append(view.endsAt()).append('|')
           .append(view.minimumBid()).append('|')
           .append(view.highestBid()).append('|')
           .append(view.count());
        List<PaymentView> payments=view.payments();
        for(int i=payments.size()-1;i>=0&&i>=payments.size()-8;i--){
            PaymentView bid=payments.get(i);
            msg.append('|').append(enc(bid.name())).append(',').append(bid.amount());
        }
        player.sendMessage(msg.toString());
    }
    private static long positive(String raw,long min,long max){
        long value=Long.parseLong(raw);
        if(value<min||value>max)throw new IllegalArgumentException();
        return value;
    }

    boolean handle(Player player,String[] args) {
        UUID id=player.getUniqueId();
        if(args.length==0){
            help(player);return true;
        }
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "state" -> {sendState(player);return true;}
            case "start" -> {
                if(isActive(id)){
                    player.sendMessage("§cStop your current auction before starting another.");
                    return true;
                }
                // /gamble auction start <minutes> <minimum> <title...>
                int minutes=5;
                long minBid=1L;
                int titleFrom=1;
                if(args.length>=4){
                    try{
                        minutes=(int)positive(args[1],1,120);
                        minBid=positive(args[2],1,1_000_000_000_000L);
                        titleFrom=3;
                    }catch(NumberFormatException|IllegalArgumentException ex){
                        player.sendMessage("§cDuration must be 1-120 minutes; minimum bid $1 to $1t.");
                        return true;
                    }
                }
                if(args.length<=titleFrom){
                    player.sendMessage("§eUsage: /gamble auction start <minutes> <minimum bid> <item>");
                    return true;
                }
                String title=String.join(" ",Arrays.copyOfRange(args,titleFrom,args.length)).trim();
                if(title.isEmpty()||title.length()>64||title.contains("|")){
                    player.sendMessage("§cAuction item name must be 1-64 characters.");return true;
                }
                Session session=new Session(title,System.currentTimeMillis());
                session.endsAt=session.started+minutes*60_000L;
                session.minimumBid=minBid;
                active.put(id,session);
                save();
                player.sendMessage("§aAuction started: §f"+title+" §7("+minutes+" min, minimum $"+money(minBid)+")");
                player.sendMessage("§7Players bid using /pay "+player.getName()+" <amount>. Money is transferred immediately.");
                player.sendMessage("§ePayments are not automatically refunded if outbid.");
                sendState(player);
                return true;
            }
            case "addtime" -> {
                Session session=active.get(id);
                if(session==null){player.sendMessage("§cNo active auction.");sendState(player);return true;}
                if(args.length!=2){player.sendMessage("§eUsage: /gamble auction addtime <minutes>");return true;}
                try{
                    long minutes=positive(args[1],1,60);
                    long newEnd=Math.max(session.endsAt,System.currentTimeMillis())+minutes*60_000L;
                    if(newEnd-System.currentTimeMillis()>240L*60_000L){
                        player.sendMessage("§cAuction cannot have more than 4 hours remaining.");return true;
                    }
                    session.endsAt=newEnd;
                    save();
                    player.sendMessage("§aAdded "+minutes+" minutes to your auction.");
                    sendState(player);
                }catch(IllegalArgumentException ex){player.sendMessage("§cChoose 1-60 minutes.");}
                return true;
            }
            case "stop" -> {
                if(!active.containsKey(id)){player.sendMessage("§cYou have no active auction.");sendState(player);return true;}
                close(id,false);
                sendState(player);
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
        player.sendMessage("§7Minimum $"+money(session.minimumBid)+" | High bid $"+money(session.highestBid));
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
        session.highestBid=Math.max(session.highestBid,amount);
        save();
        Player receiver=Bukkit.getPlayer(receiverId);
        if(receiver!=null){
            sendState(receiver);
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
                config.set(path+".endsAt",session.endsAt);
                config.set(path+".minimumBid",session.minimumBid);
                config.set(path+".highestBid",session.highestBid);
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
                session.endsAt=config.getLong(path+".endsAt");
                session.minimumBid=config.getLong(path+".minimumBid",1L);
                session.highestBid=config.getLong(path+".highestBid");
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
