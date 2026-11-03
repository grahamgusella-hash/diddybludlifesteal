package com.example.diddybludpaper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Original server-side in-game-money arcade.
 * No real-money payments, purchases, or withdrawals.
 */
final class DiddybludArcade implements Listener {
    private final DiddybludPaper plugin;
    private final PlayerAuctionLog auctionLog;
    private final Set<UUID> awaitingAuctionTitle=new HashSet<>();
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Long> selectedStake = new HashMap<>();
    private final Map<UUID, Stats> playerStats = new HashMap<>();
    private final File statsFile;
    private static final long[] STAKES={100L,1_000L,10_000L,100_000L,1_000_000L};
    private static final String TITLE = "§5§lDiddyblud Arcade";

    private static final class Stats {
        long played, wins, wagered, received;
    }
    private static final class ArcadeHolder implements InventoryHolder {
        Inventory inventory;
        String tab="games";
        int page=0;
        @Override public Inventory getInventory(){return inventory;}
    }

    DiddybludArcade(DiddybludPaper plugin,PlayerAuctionLog auctionLog) {
        this.plugin=plugin;
        this.auctionLog=auctionLog;
        this.statsFile=new File(plugin.getDataFolder(),"arcade-stats.yml");
        load();
    }

    void open(Player player) {
        ArcadeHolder holder=new ArcadeHolder();
        Inventory inv=Bukkit.createInventory(holder,27,TITLE);
        holder.inventory=inv;
        long stake=selectedStake.getOrDefault(player.getUniqueId(),1_000L);
        inv.setItem(4,item(Material.GOLD_INGOT,"§eStake: $"+fmt(stake),
            "§7Click a stake below to change your bet","§7Your balance: $"+fmt(plugin.funds(player.getUniqueId()))));
        Material[] stakeIcons={Material.COPPER_INGOT,Material.IRON_INGOT,Material.GOLD_INGOT,Material.DIAMOND,Material.NETHER_STAR};
        for(int i=0;i<STAKES.length;i++){
            inv.setItem(9+i,item(stakeIcons[i],(stake==STAKES[i]?"§a✔ ":"§e")+"$"+fmt(STAKES[i]),"§7Click to select stake"));
        }
        inv.setItem(15,item(Material.GOLD_NUGGET,"§6Coin Flip","§7Win chance: 50%","§7Win payout: 2x stake","§eClick to play"));
        inv.setItem(16,item(Material.REDSTONE,"§cHigh Roll","§7Win if a six-sided die rolls 5 or 6","§7Win payout: 3x stake","§eClick to play"));
        inv.setItem(17,item(Material.CLOCK,"§dLucky Sevens","§7Win chance: 1 in 8","§7Win payout: 8x stake","§eClick to play"));
        Stats stats=playerStats.getOrDefault(player.getUniqueId(),new Stats());
        inv.setItem(22,item(Material.BOOK,"§bYour Arcade Stats",
            "§7Games: §f"+stats.played,"§7Wins: §f"+stats.wins,
            "§7Wagered: §f$"+fmt(stats.wagered),
            "§7Paid out: §f$"+fmt(stats.received)));
        inv.setItem(26,item(Material.CHEST,"§6§lAuctions §7(Tab)","§eClick to open payment auction tracker"));
        player.openInventory(inv);
    }

    private void openAuctions(Player player,int page){
        ArcadeHolder holder=new ArcadeHolder();
        holder.tab="auctions";
        holder.page=page;
        Inventory inv=Bukkit.createInventory(holder,54,"§6§lDiddyblud Auctions");
        holder.inventory=inv;
        PlayerAuctionLog.AuctionView view=auctionLog.view(player.getUniqueId());
        inv.setItem(4,item(Material.CHEST,"§6§lAuctions","§7Payments received via /pay are logged","§7Only while your auction is active"));
        inv.setItem(10,item(Material.LIME_DYE,"§aStart Auction","§7Click, then type an auction title in chat","§7Type cancel to go back"));
        inv.setItem(12,item(Material.BOOK,"§eView Auction Payments",
            view==null?"§7No auction history yet":"§7"+view.title()+" | "+view.count()+" payments",
            view==null?"§7Start an auction first":"§7Total paid: $"+fmt(view.total())));
        inv.setItem(14,item(Material.RED_DYE,"§cStop Active Auction",
            auctionLog.isActive(player.getUniqueId())?"§eClick to close and save the log":"§7No active auction"));
        inv.setItem(49,item(Material.ARROW,"§eBack to Games"));
        inv.setItem(45,item(Material.ARROW,"§ePrevious Payments"));
        inv.setItem(53,item(Material.ARROW,"§eNext Payments"));
        if(view!=null){
            int start=page*27;
            List<PlayerAuctionLog.PaymentView> payments=view.payments();
            for(int i=0;i<27 && start+i<payments.size();i++){
                PlayerAuctionLog.PaymentView payment=payments.get(payments.size()-1-start-i);
                inv.setItem(18+i,item(Material.PAPER,"§a$"+fmt(payment.amount()),
                    "§7From: §f"+payment.name(),
                    "§7Time: §f"+java.time.Instant.ofEpochMilli(payment.time()).toString()));
            }
        }
        player.openInventory(inv);
    }

    private void auctionClick(Player player,int slot,int page){
        UUID id=player.getUniqueId();
        if(slot==49){open(player);return;}
        if(slot==45){openAuctions(player,Math.max(0,page-1));return;}
        if(slot==53){
            PlayerAuctionLog.AuctionView view=auctionLog.view(id);
            if(view!=null && (page+1)*27<view.payments().size())openAuctions(player,page+1);
            return;
        }
        if(slot==10){
            if(auctionLog.isActive(id)){
                player.sendMessage("§cStop your current auction before starting a new one.");return;
            }
            awaitingAuctionTitle.add(id);
            player.closeInventory();
            player.sendMessage("§eEnter an auction title in chat (up to 64 characters), or type §fcancel§e.");
            return;
        }
        if(slot==12){openAuctions(player,0);return;}
        if(slot==14){
            auctionLog.handle(player,new String[]{"stop"});
            openAuctions(player,0);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onAuctionChat(AsyncPlayerChatEvent event){
        UUID id=event.getPlayer().getUniqueId();
        if(!awaitingAuctionTitle.remove(id))return;
        event.setCancelled(true);
        String title=event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin,()->{
            Player player=Bukkit.getPlayer(id);
            if(player==null)return;
            if(!title.equalsIgnoreCase("cancel") && !title.isBlank() && title.length()<=64){
                auctionLog.handle(player,new String[]{"start",title});
            }else if(!title.equalsIgnoreCase("cancel")){
                player.sendMessage("§cAuction title must be 1 to 64 characters.");
            }
            openAuctions(player,0);
        });
    }
    @EventHandler public void onQuit(PlayerQuitEvent event){
        awaitingAuctionTitle.remove(event.getPlayer().getUniqueId());
    }

    private static ItemStack item(Material material,String name,String... lore) {
        ItemStack stack=new ItemStack(material);
        ItemMeta meta=stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        stack.setItemMeta(meta);
        return stack;
    }
    private static String fmt(long n){return String.format(Locale.US,"%,d",n);}

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof ArcadeHolder holder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        int slot=event.getRawSlot();
        if(holder.tab.equals("auctions")){
            if(slot>=0&&slot<54)auctionClick(player,slot,holder.page);
            return;
        }
        if(slot==26){openAuctions(player,0);return;}
        if(slot<9||slot>=18)return;
        if(slot<=13){
            selectedStake.put(player.getUniqueId(),STAKES[slot-9]);
            open(player);
        }else if(slot>=15&&slot<=17)play(player,slot);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event){
        if(event.getView().getTopInventory().getHolder() instanceof ArcadeHolder)event.setCancelled(true);
    }

    private void play(Player player,int gameSlot) {
        UUID id=player.getUniqueId();
        long stake=selectedStake.getOrDefault(id,1_000L);
        if(stake<=0||stake>1_000_000L){player.sendMessage("§cInvalid arcade stake.");return;}
        if(plugin.funds(id)<stake){player.sendMessage("§cNot enough money for that bet.");return;}
        long multiplier=gameSlot==15?2:gameSlot==16?3:8;
        boolean won=gameSlot==15?random.nextBoolean():gameSlot==16?random.nextInt(6)>=4:random.nextInt(8)==0;
        long payout=won?stake*multiplier:0;
        // Never charge money unless a winning payout is safe to credit.
        if(payout>0 && plugin.funds(id)-stake>Long.MAX_VALUE-payout){
            player.sendMessage("§cBalance is too high for this payout.");return;
        }
        if(!plugin.charge(id,stake)){player.sendMessage("§cCouldn't place bet.");return;}
        if(payout>0&&!plugin.credit(id,payout)){
            plugin.credit(id,stake);
            player.sendMessage("§cBet cancelled; payout could not be completed.");
            return;
        }
        Stats stats=playerStats.computeIfAbsent(id,k->new Stats());
        stats.played++;
        if(won)stats.wins++;
        stats.wagered+=stake;
        stats.received+=payout;
        save();
        player.sendMessage(won?
            "§aYou won $"+fmt(payout)+"! §7(Net +$"+fmt(payout-stake)+")":
            "§cYou lost $"+fmt(stake)+".");
        open(player);
    }

    private void load(){
        YamlConfiguration config=YamlConfiguration.loadConfiguration(statsFile);
        for(String str:config.getKeys(false)){
            try{
                UUID id=UUID.fromString(str);
                Stats stats=new Stats();
                stats.played=config.getLong(str+".played");
                stats.wins=config.getLong(str+".wins");
                stats.wagered=config.getLong(str+".wagered");
                stats.received=config.getLong(str+".received");
                playerStats.put(id,stats);
            }catch(IllegalArgumentException ignored){}
        }
    }
    void save(){
        YamlConfiguration config=new YamlConfiguration();
        playerStats.forEach((id,s)->{
            String base=id.toString();
            config.set(base+".played",s.played);
            config.set(base+".wins",s.wins);
            config.set(base+".wagered",s.wagered);
            config.set(base+".received",s.received);
        });
        try{config.save(statsFile);}
        catch(IOException ex){plugin.getLogger().warning("Could not save arcade stats: "+ex.getMessage());}
    }
}
