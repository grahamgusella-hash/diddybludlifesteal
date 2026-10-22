package com.example.lifesteal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Server-controlled chest menus: buttons cannot be taken by players. Data is session-only. */
public final class SmpMenus {
    private SmpMenus() {}
    private static final Map<UUID, Long> CASH = LifestealMod.economy();
    private static final Map<String, Long> PRICES = LifestealMod.shopPrices();
    private record Listing(UUID owner, ItemStack stack, long price) {}
    private record Order(UUID owner, String itemId, int count, long unitPrice) {}
    private static final Map<Integer, Listing> AUCTIONS = new LinkedHashMap<>();
    private static final Map<Integer, Order> ORDERS = new LinkedHashMap<>();
    private static int nextListing = 1, nextOrder = 1;
    private static UUID queue = null;

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            dispatcher.register(literal("shop").executes(c -> { shop(c.getSource().getPlayerOrException(), false); return 1; }));
            dispatcher.register(literal("orders").executes(c -> { orders(c.getSource().getPlayerOrException(),0); return 1; }));
            dispatcher.register(literal("order")
                .then(literal("create").then(argument("item", StringArgumentType.word())
                    .then(argument("count",IntegerArgumentType.integer(1,64))
                    .then(argument("each",IntegerArgumentType.integer(1))
                    .executes(c -> createOrder(c.getSource().getPlayerOrException(), StringArgumentType.getString(c,"item"),
                        IntegerArgumentType.getInteger(c,"count"), IntegerArgumentType.getInteger(c,"each"))))))));
            dispatcher.register(literal("ah").executes(c -> { auctions(c.getSource().getPlayerOrException(),0); return 1; })
                .then(literal("sell").then(argument("price",IntegerArgumentType.integer(1))
                .executes(c -> sellAuction(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c,"price"))))));
            dispatcher.register(literal("auction").executes(c -> { auctions(c.getSource().getPlayerOrException(),0); return 1; }));
            dispatcher.register(literal("rtp").executes(c -> { rtpMenu(c.getSource().getPlayerOrException()); return 1; }));
            dispatcher.register(literal("rtpq").executes(c -> { queueMenu(c.getSource().getPlayerOrException()); return 1; })
                .then(literal("leave").executes(c -> leaveQueue(c.getSource().getPlayerOrException()))));
        });
    }
    private static void message(ServerPlayer p,String s) {p.sendSystemMessage(Component.literal(s));}
    private static ItemStack icon(Item item, String label) {
        ItemStack stack = new ItemStack(item);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal(label));
        return stack;
    }
    private interface SlotHandler {void click(int slot, int button, ContainerInput type);}
    private static void menu(ServerPlayer p, String title, ItemStack[] items, SlotHandler handler) {
        SimpleContainer contents=new SimpleContainer(27);
        for(int i=0;i<Math.min(27,items.length);i++) if(items[i]!=null) contents.setItem(i,items[i]);
        p.openMenu(new SimpleMenuProvider((id,inv,user)->new ChestMenu(MenuType.GENERIC_9x3,id,inv,contents,3) {
            @Override public void clicked(int slot,int button,ContainerInput type,Player player) {
                // The 27 GUI slots are never movable: no free-items exploit.
                if(slot>=0 && slot<27) { if(player instanceof ServerPlayer) handler.click(slot,button,type); return; }
                if(type==ContainerInput.QUICK_MOVE || type==ContainerInput.SWAP || type==ContainerInput.PICKUP_ALL || type==ContainerInput.QUICK_CRAFT) return;
                super.clicked(slot,button,type,player);
            }
        },Component.literal(title)));
    }
    private static Item getItem(String id) {
        try { return BuiltInRegistries.ITEM.getValue(Identifier.parse(id)); }
        catch(Exception e) {return null;}
    }
    private static long balance(UUID id) {return CASH.getOrDefault(id,0L);}
    private static synchronized boolean charge(UUID id,long price) {
        if(price<0 || balance(id)<price) return false;
        CASH.put(id,balance(id)-price); return true;
    }
    private static synchronized void reward(UUID id,long amount) {
        if(amount<0 || amount>Long.MAX_VALUE-balance(id)) throw new IllegalArgumentException("Balance overflow");
        CASH.put(id,balance(id)+amount);
    }
    private static void shop(ServerPlayer p,boolean edit) {
        ItemStack[] icons=new ItemStack[27];
        List<String> ids=new ArrayList<>(PRICES.keySet());
        for(int i=0;i<Math.min(ids.size(),18);i++) {
            String id=ids.get(i); Item item=getItem(id);
            if(item!=null) icons[i]=icon(item,id+" | Buy $"+PRICES.get(id)+" | Sell $"+(PRICES.get(id)/2));
        }
        boolean op=Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(p.createCommandSourceStack());
        icons[22]=icon(Items.EMERALD,"Balance: $"+balance(p.getUUID()));
        if(op) icons[26]=icon(Items.COMPARATOR, edit?"Admin editing ON (click to exit)":"Admin Settings (click)");
        if(edit) icons[19]=icon(Items.ANVIL,"Hold an item and click to add it to shop");
        menu(p,edit?"Shop — Admin Editor":"SMP Shop",icons,(slot,button,type)->{
            if(slot==26 && op) {shop(p,!edit);return;}
            if(slot==19 && edit && op) {
                ItemStack held=p.getMainHandItem();
                if(!held.isEmpty()) {String id=BuiltInRegistries.ITEM.getKey(held.getItem()).toString(); PRICES.putIfAbsent(id,100L);shop(p,true);}
                else message(p,"Hold the item you want to add in your main hand.");
                return;
            }
            if(slot>=ids.size() || slot>=18)return;
            String id=ids.get(slot); long price=PRICES.get(id); Item item=getItem(id);
            if(item==null)return;
            if(edit && op) {
                if(type==ContainerInput.QUICK_MOVE) PRICES.remove(id);
                else if(button==0) PRICES.put(id,Math.min(Integer.MAX_VALUE,price+100));
                else PRICES.put(id,Math.max(0,price-100));
                shop(p,true);return;
            }
            if(button==0) {
                if(p.getInventory().getFreeSlot()<0){message(p,"Make inventory room.");return;}
                if(!charge(p.getUUID(),price)){message(p,"Not enough money.");return;}
                p.getInventory().add(new ItemStack(item));shop(p,false);
            } else {
                // Sell ONE matching item, excluding container GUI icons.
                boolean sold=false;
                for(int i=0;i<p.getInventory().getContainerSize();i++) {
                    ItemStack stack=p.getInventory().getItem(i);
                    if(stack.is(item) && !stack.isEmpty()) {try {reward(p.getUUID(),price/2);}catch(IllegalArgumentException ex){message(p,"Balance too high.");return;}
                        stack.shrink(1);sold=true;break;}
                }
                if(!sold) message(p,"You don't have this item.");
                else shop(p,false);
            }
        });
    }
    private static int sellAuction(ServerPlayer p,long price) {
        ItemStack held=p.getMainHandItem();
        if(held.isEmpty()){message(p,"Hold an item stack to auction.");return 0;}
        synchronized(AUCTIONS) {AUCTIONS.put(nextListing++,new Listing(p.getUUID(),held.copy(),price));}
        held.setCount(0);message(p,"Auction listed. Use /ah to see it.");return 1;
    }
    private static void auctions(ServerPlayer p,int page) {
        List<Map.Entry<Integer,Listing>> all;
        synchronized(AUCTIONS){all=new ArrayList<>(AUCTIONS.entrySet());}
        int start=Math.max(0,page)*18;
        ItemStack[] icons=new ItemStack[27];
        for(int i=0;i<18 && start+i<all.size();i++){
            var entry=all.get(start+i);icons[i]=entry.getValue().stack().copy();
            icons[i].set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Auction #"+entry.getKey()+" $"+entry.getValue().price()));
        }
        icons[18]=icon(Items.ARROW,"Previous page");icons[26]=icon(Items.ARROW,"Next page");
        icons[22]=icon(Items.GOLD_INGOT,"Use /ah sell <price> while holding a stack");
        menu(p,"Auction House — Page "+(page+1),icons,(slot,b,type)->{
            if(slot==18){auctions(p,Math.max(0,page-1));return;}
            if(slot==26){auctions(p,page+1);return;}
            if(slot>=18 || start+slot>=all.size())return;
            int key=all.get(start+slot).getKey();
            synchronized(AUCTIONS) {
                Listing listing=AUCTIONS.get(key);if(listing==null){auctions(p,page);return;}
                if(listing.owner().equals(p.getUUID())){
                    if(p.getInventory().getFreeSlot()<0){message(p,"Make inventory room to cancel listing.");return;}
                    AUCTIONS.remove(key);p.getInventory().add(listing.stack().copy());message(p,"Listing cancelled.");
                } else {
                    if(p.getInventory().getFreeSlot()<0){message(p,"Make inventory room.");return;}
                    if(listing.price()>Long.MAX_VALUE-balance(listing.owner())){message(p,"Seller balance limit reached.");return;}
                    if(!charge(p.getUUID(),listing.price())){message(p,"Not enough money.");return;}
                    reward(listing.owner(),listing.price());AUCTIONS.remove(key);p.getInventory().add(listing.stack().copy());message(p,"Auction purchased.");
                }
            }
            auctions(p,page);
        });
    }
    private static int createOrder(ServerPlayer p,String itemId,int count,int each) {
        Item item=getItem(itemId);
        if(item==null || item==Items.AIR){message(p,"Unknown item id. Example: minecraft:diamond");return 0;}
        long total=(long)count*each;
        if(!charge(p.getUUID(),total)){message(p,"You need $"+total+" to fund the order.");return 0;}
        synchronized(ORDERS){ORDERS.put(nextOrder++,new Order(p.getUUID(),itemId,count,each));}
        message(p,"Order created and $"+total+" held in escrow. Open /orders.");return 1;
    }
    private static void orders(ServerPlayer p,int page) {
        List<Map.Entry<Integer,Order>> all;
        synchronized(ORDERS){all=new ArrayList<>(ORDERS.entrySet());}
        int start=page*18;ItemStack[] icons=new ItemStack[27];
        for(int i=0;i<18&&start+i<all.size();i++){
            var e=all.get(start+i); Item item=getItem(e.getValue().itemId());
            if(item!=null)icons[i]=icon(item,"Order #"+e.getKey()+": "+e.getValue().count()+" x $"+e.getValue().unitPrice());
        }
        icons[18]=icon(Items.ARROW,"Previous page");icons[26]=icon(Items.ARROW,"Next page");
        icons[22]=icon(Items.PAPER,"Create: /order create minecraft:diamond 10 100");
        menu(p,"Buy Orders — Page "+(page+1),icons,(slot,b,t)->{
            if(slot==18){orders(p,Math.max(0,page-1));return;}
            if(slot==26){orders(p,page+1);return;}
            if(slot>=18||start+slot>=all.size())return;
            int key=all.get(start+slot).getKey();
            synchronized(ORDERS){
                Order order=ORDERS.get(key);if(order==null){orders(p,page);return;}
                long total=(long)order.count()*order.unitPrice();
                if(order.owner().equals(p.getUUID())) {
                    if(total>Long.MAX_VALUE-balance(p.getUUID())){message(p,"Balance cannot hold refund.");return;}
                    ORDERS.remove(key);reward(p.getUUID(),total);message(p,"Order cancelled and refunded.");
                } else {
                    Item item=getItem(order.itemId());
                    int available=0;
                    for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))available+=p.getInventory().getItem(i).getCount();
                    if(available<order.count()){message(p,"You need "+order.count()+" of "+order.itemId());return;}
                    if(total>Long.MAX_VALUE-balance(p.getUUID())){message(p,"Balance cannot hold payment.");return;}
                    ServerPlayer buyer=p.level().getServer().getPlayerList().getPlayer(order.owner());
                    if(buyer==null || buyer.getInventory().getFreeSlot()<0){message(p,"Order owner must be online with inventory space.");return;}
                    int left=order.count();
                    for(int i=0;i<p.getInventory().getContainerSize()&&left>0;i++){
                        ItemStack stack=p.getInventory().getItem(i);
                        if(stack.is(item)){int take=Math.min(left,stack.getCount());stack.shrink(take);left-=take;}
                    }
                    buyer.getInventory().add(new ItemStack(item,order.count()));ORDERS.remove(key);reward(p.getUUID(),total);
                    message(p,"Order filled for $"+total);message(buyer,"Your buy order was filled!");
                }
            }
            orders(p,page);
        });
    }
    private static void rtpMenu(ServerPlayer p) {
        ItemStack[] icons=new ItemStack[27];
        icons[11]=icon(Items.GRASS_BLOCK,"Random teleport (near): up to 2,000 blocks");
        icons[15]=icon(Items.ENDER_PEARL,"Random teleport (far): 2,000–8,000 blocks");
        icons[22]=icon(Items.BARRIER,"Cancel");
        menu(p,"Random Teleport",icons,(slot,b,type)->{
            if(slot==11) randomTeleport(p,false);
            if(slot==15) randomTeleport(p,true);
            if(slot==22)p.closeContainer();
        });
    }
    private static void randomTeleport(ServerPlayer p,boolean far) {
        Random r=new Random();
        for(int attempt=0;attempt<20;attempt++){
            int radius=far?2000+r.nextInt(6001):200+r.nextInt(1801);
            double angle=r.nextDouble()*Math.PI*2;
            int x=(int)Math.round(Math.cos(angle)*radius), z=(int)Math.round(Math.sin(angle)*radius);
            // Heightmap in the CURRENT dimension: avoid cross-dimension API incompatibility.
            BlockPos ground=p.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,64,z));
            BlockPos feet=ground;
            if(feet.getY() < -60 || feet.getY() > 315)continue;
            if(!p.level().getBlockState(feet.below()).getFluidState().isEmpty())continue;
            if(!p.level().getBlockState(feet).isAir() || !p.level().getBlockState(feet.above()).isAir())continue;
            p.closeContainer();p.teleportTo(feet.getX()+0.5,feet.getY(),feet.getZ()+0.5);
            message(p,"Randomly teleported!");return;
        }
        message(p,"Couldn't find a safe destination. Try again.");
    }
    private static void queueMenu(ServerPlayer p) {
        ItemStack[] icons=new ItemStack[27];
        icons[11]=icon(Items.EMERALD,"Join RTP queue (2 players)");
        icons[15]=icon(Items.BARRIER,"Leave queue");
        menu(p,"RTP Queue",icons,(slot,b,type)->{
            if(slot==11)joinQueue(p);if(slot==15)leaveQueue(p);
        });
    }
    private static synchronized void joinQueue(ServerPlayer p) {
        if(queue==null || queue.equals(p.getUUID())) {queue=p.getUUID();message(p,"Waiting for a second player. /rtpq leave to exit.");p.closeContainer();return;}
        ServerPlayer other=p.level().getServer().getPlayerList().getPlayer(queue);
        queue=null;
        if(other==null){queue=p.getUUID();message(p,"Waiting for a second player.");p.closeContainer();return;}
        // Both players must be in the same dimension; teleport to the destination together.
        if(other.level()!=p.level()){message(p,"Queue partner is in another dimension. Join again.");return;}
        randomTeleport(p,false);
        other.teleportTo(p.getX()+1,p.getY(),p.getZ());
        message(other,"Your RTP queue matched with "+p.getName().getString());
    }
    private static synchronized int leaveQueue(ServerPlayer p) {
        if(p.getUUID().equals(queue)){queue=null;message(p,"Left RTP queue.");}else message(p,"You are not in the queue.");
        p.closeContainer();return 1;
    }
}
