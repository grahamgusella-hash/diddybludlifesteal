package com.example.lifesteal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
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
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Server-controlled chest menus. WARNING: currency, auctions and orders are memory-only. */
public final class SmpMenus {
    private SmpMenus() {}
    private static final Map<UUID,Long> CASH=LifestealMod.economy();
    private static final Map<String,Long> PRICES=LifestealMod.shopPrices();
    private record Listing(UUID owner,ItemStack stack,long price) {}
    private record Order(UUID owner,String itemId,int count,long unitPrice) {}
    private static final Map<Integer,Listing> AUCTIONS=new LinkedHashMap<>();
    private static final Map<Integer,Order> ORDERS=new LinkedHashMap<>();
    private static int nextListing=1,nextOrder=1;
    private static UUID queue=null;
    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env)->{
            dispatcher.register(literal("customitems")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c->{customItems(c.getSource().getPlayerOrException());return 1;}));
            dispatcher.register(literal("shop").executes(c->{shop(c.getSource().getPlayerOrException(),false);return 1;}));
            dispatcher.register(literal("orders").executes(c->{orders(c.getSource().getPlayerOrException(),0);return 1;}));
            dispatcher.register(literal("order").then(literal("create")
                .then(argument("item",StringArgumentType.word())
                .then(argument("count",IntegerArgumentType.integer(1,64))
                .then(argument("each",IntegerArgumentType.integer(1))
                .executes(c->createOrder(c.getSource().getPlayerOrException(),
                    StringArgumentType.getString(c,"item"),IntegerArgumentType.getInteger(c,"count"),
                    IntegerArgumentType.getInteger(c,"each"))))))));
            dispatcher.register(literal("ah").executes(c->{auctions(c.getSource().getPlayerOrException(),0);return 1;})
                .then(literal("sell").then(argument("price",IntegerArgumentType.integer(1))
                .executes(c->sellAuction(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"price"))))));
            dispatcher.register(literal("auction").executes(c->{auctions(c.getSource().getPlayerOrException(),0);return 1;}));
            dispatcher.register(literal("rtp").executes(c->{rtpMenu(c.getSource().getPlayerOrException());return 1;}));
            dispatcher.register(literal("rtpq").executes(c->{queueMenu(c.getSource().getPlayerOrException());return 1;})
                .then(literal("leave").executes(c->leaveQueue(c.getSource().getPlayerOrException()))));
        });
    }
    private static void message(ServerPlayer p,String s){p.sendSystemMessage(Component.literal(s));}
    private static ItemStack icon(Item item,String label){
        ItemStack stack=new ItemStack(item);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal(label));
        return stack;
    }
    private interface SlotHandler {void click(int slot,int button,ContainerInput type);}
    private static void menu(ServerPlayer p,String title,ItemStack[] items,SlotHandler handler){
        SimpleContainer contents=new SimpleContainer(27);
        for(int i=0;i<Math.min(27,items.length);i++)if(items[i]!=null)contents.setItem(i,items[i]);
        p.openMenu(new SimpleMenuProvider((id,inv,user)->new ChestMenu(MenuType.GENERIC_9x3,id,inv,contents,3){
            @Override public void clicked(int slot,int button,ContainerInput type,Player player){
                if(slot>=0 && slot<27){if(player instanceof ServerPlayer)handler.click(slot,button,type);return;}
                if(type==ContainerInput.QUICK_MOVE||type==ContainerInput.SWAP||
                   type==ContainerInput.PICKUP_ALL||type==ContainerInput.QUICK_CRAFT)return;
                super.clicked(slot,button,type,player);
            }
        },Component.literal(title)));
    }
    private static Item getItem(String id){
        try{return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));}catch(Exception e){return null;}
    }
    private static long balance(UUID id){return CASH.getOrDefault(id,0L);}
    private static synchronized boolean charge(UUID id,long price){
        if(price<0||balance(id)<price)return false;
        CASH.put(id,balance(id)-price);return true;
    }
    private static synchronized void reward(UUID id,long amount){
        if(amount<0||amount>Long.MAX_VALUE-balance(id))throw new IllegalArgumentException("Balance overflow");
        CASH.put(id,balance(id)+amount);
    }
    /** OP-only server-side menu; ordinary clients see a vanilla chest inventory. */
    private static void customItems(ServerPlayer p){
        ItemStack[] icons = new ItemStack[27];
        icons[10] = icon(LifestealMod.HEART, "Heart — click to receive 1");
        // Mob spawner entries are currently generic spawner items.
        // Named variants cannot retain their mob type until block entity data support is implemented.
        String[] mobs = {"Zombie", "Skeleton", "Spider", "Creeper", "Enderman",
                         "Blaze", "Pig", "Cow", "Sheep", "Chicken", "Iron Golem"};
        int[] positions = {11,12,13,14,15,16,19,20,21,22,23};
        for (int i=0;i<mobs.length;i++)
            icons[positions[i]] = icon(Items.SPAWNER, mobs[i]+" Spawner (placeholder)");
        icons[26] = icon(Items.BARRIER,"Close");
        menu(p,"Custom Items (OP)",icons,(slot,button,type)->{
            // Re-check OP permission on each click, not only at command registration.
            if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(p.createCommandSourceStack())) {
                p.closeContainer();
                return;
            }
            if (slot==26){p.closeContainer();return;}
            if (p.getInventory().getFreeSlot()<0) {
                message(p,"Make room in your inventory first.");return;
            }
            if (slot==10) {
                p.getInventory().add(new ItemStack(LifestealMod.HEART));
                message(p,"Received one Heart.");
                return;
            }
            for(int position:positions) if(slot==position) {
                message(p,"Mob-specific spawners are not implemented yet; no item given.");
                return;
            }
        });
    }

    // Main shop categories follow the positions in the supplied Donut SMP screenshot.
    private record ShopCategory(String id, String title, Item icon, int slot) {}
    private static final List<ShopCategory> SHOP_CATEGORIES = List.of(
        new ShopCategory("totems", "Totems & Combat", Items.TOTEM_OF_UNDYING, 12),
        new ShopCategory("enchants", "Enchantments", Items.ENCHANTED_BOOK, 13),
        new ShopCategory("ores", "Ores & Valuables", Items.DIAMOND, 14),
        new ShopCategory("drops", "Mob Drops", Items.BLAZE_POWDER, 20),
        new ShopCategory("wood", "Wood & Blocks", Items.OAK_LOG, 21),
        new ShopCategory("farming", "Farming & Food", Items.CARROT, 22),
        new ShopCategory("storage", "Storage & Utility", Items.CHEST, 23),
        new ShopCategory("redstone", "Redstone", Items.REDSTONE, 24)
    );
    // Category choices made using the OP editor are temporary until server persistence is added.
    private static final Map<String,String> SHOP_CATEGORY_OVERRIDES = new HashMap<>();

    private static String categoryOf(String id){
        String assigned=SHOP_CATEGORY_OVERRIDES.get(id);
        if(assigned!=null)return assigned;
        String s=id.toLowerCase(Locale.ROOT);
        if(s.contains("heart")||s.contains("totem")||s.contains("sword")||
           s.contains("shield")||s.contains("armor")||s.contains("golden_apple"))return "totems";
        if(s.contains("enchant")||s.contains("book")||s.contains("anvil")||
           s.contains("experience"))return "enchants";
        if(s.contains("diamond")||s.contains("netherite")||s.contains("emerald")||
           s.contains("iron")||s.contains("gold")||s.contains("ore")||
           s.contains("lapis")||s.contains("copper"))return "ores";
        if(s.contains("blaze")||s.contains("bone")||s.contains("gunpowder")||
           s.contains("string")||s.contains("rotten")||s.contains("ender_pearl")||
           s.contains("slime")||s.contains("ghast"))return "drops";
        if(s.contains("log")||s.contains("plank")||s.contains("wood")||
           s.contains("stone")||s.contains("dirt")||s.contains("cobble")||
           s.contains("brick"))return "wood";
        if(s.contains("carrot")||s.contains("potato")||s.contains("seed")||
           s.contains("wheat")||s.contains("apple")||s.contains("bread")||
           s.contains("beef")||s.contains("pork"))return "farming";
        if(s.contains("redstone")||s.contains("repeater")||s.contains("piston")||
           s.contains("observer")||s.contains("comparator")||s.contains("hopper"))return "redstone";
        return "storage";
    }
    private static ShopCategory shopCategory(String id){
        for(ShopCategory category:SHOP_CATEGORIES)if(category.id().equals(id))return category;
        return SHOP_CATEGORIES.get(0);
    }
    private static boolean isShopOp(ServerPlayer p){
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(p.createCommandSourceStack());
    }
    private static void shop(ServerPlayer p,boolean edit){
        boolean op=isShopOp(p);
        if(!op)edit=false;
        final boolean editing=edit;
        ItemStack[] icons=new ItemStack[27];
        for(ShopCategory category:SHOP_CATEGORIES)
            icons[category.slot()]=icon(category.icon(),category.title()+" - Click to browse");
        if(op)icons[26]=icon(Items.COMPARATOR,editing?"Admin editing ON - Click to exit":"Admin Settings - Click to edit");
        menu(p,editing?"Shop | Admin Menu":"Shop | Menu",icons,(slot,button,type)->{
            if(slot==26&&isShopOp(p)){shop(p,!editing);return;}
            for(ShopCategory category:SHOP_CATEGORIES)
                if(slot==category.slot()){
                    shopItems(p,category.id(),editing,0);return;
                }
        });
    }
    private static void shopItems(ServerPlayer p,String categoryId,boolean edit,int page){
        boolean op=isShopOp(p);
        if(!op)edit=false;
        final boolean editing=edit;
        List<String> ids=new ArrayList<>();
        for(String id:PRICES.keySet())if(categoryId.equals(categoryOf(id)))ids.add(id);
        final int offset=Math.max(0,page)*18;
        ItemStack[] icons=new ItemStack[27];
        for(int i=0;i<18&&offset+i<ids.size();i++){
            String id=ids.get(offset+i);
            Item item=getItem(id);
            if(item!=null)icons[i]=icon(item,id+" | Buy $"+PRICES.get(id)+" | Sell $"+PRICES.get(id)/2);
        }
        icons[18]=icon(Items.ARROW,"Back to shop categories");
        icons[19]=icon(Items.PAPER,"Previous page");
        icons[25]=icon(Items.PAPER,"Next page");
        icons[22]=icon(Items.EMERALD,"Balance: $"+balance(p.getUUID()));
        if(editing)icons[20]=icon(Items.ANVIL,"Hold an item and click to add it to this category");
        if(op)icons[26]=icon(Items.COMPARATOR,editing?"Admin: Left +$100, right -$100, shift-click removes":"Admin Settings");
        menu(p,shopCategory(categoryId).title()+(editing?" | Edit":" | Shop"),icons,(slot,button,type)->{
            if(slot==18){shop(p,editing);return;}
            if(slot==19){shopItems(p,categoryId,editing,Math.max(0,page-1));return;}
            if(slot==25){if(offset+18<ids.size())shopItems(p,categoryId,editing,page+1);return;}
            if(slot==26&&isShopOp(p)){shopItems(p,categoryId,!editing,page);return;}
            if(slot==20&&editing&&isShopOp(p)){
                ItemStack held=p.getMainHandItem();
                if(held.isEmpty()){message(p,"Hold an item to add it to this category.");return;}
                String id=BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                PRICES.putIfAbsent(id,100L);
                SHOP_CATEGORY_OVERRIDES.put(id,categoryId);
                shopItems(p,categoryId,true,0);return;
            }
            if(slot<0||slot>=18||offset+slot>=ids.size())return;
            String id=ids.get(offset+slot);
            Long priceObj=PRICES.get(id);
            if(priceObj==null)return;
            long price=priceObj;
            Item item=getItem(id);
            if(item==null)return;
            if(editing&&isShopOp(p)){
                if(type==ContainerInput.QUICK_MOVE){
                    PRICES.remove(id);SHOP_CATEGORY_OVERRIDES.remove(id);
                }else if(button==0){
                    PRICES.put(id,Math.min(Integer.MAX_VALUE,price+100));
                }else{
                    PRICES.put(id,Math.max(0,price-100));
                }
                shopItems(p,categoryId,true,page);return;
            }
            if(button==0){
                if(p.getInventory().getFreeSlot()<0){message(p,"Make room in your inventory.");return;}
                if(!charge(p.getUUID(),price)){message(p,"Not enough money.");return;}
                p.getInventory().add(new ItemStack(item));
                shopItems(p,categoryId,false,page);
            }else{
                boolean sold=false;
                for(int i=0;i<p.getInventory().getContainerSize();i++){
                    ItemStack stack=p.getInventory().getItem(i);
                    if(!stack.isEmpty()&&stack.is(item)){
                        try{reward(p.getUUID(),price/2);}
                        catch(IllegalArgumentException ex){message(p,"Balance too high.");return;}
                        stack.shrink(1);sold=true;break;
                    }
                }
                if(!sold)message(p,"You don't have this item.");
                else shopItems(p,categoryId,false,page);
            }
        });
    }
    private static int sellAuction(ServerPlayer p,long price){
        ItemStack held=p.getMainHandItem();
        if(held.isEmpty()){message(p,"Hold an item stack to auction.");return 0;}
        synchronized(AUCTIONS){AUCTIONS.put(nextListing++,new Listing(p.getUUID(),held.copy(),price));}
        held.setCount(0);message(p,"Auction listed. Use /ah.");return 1;
    }
    private static void auctions(ServerPlayer p,int page){
        List<Map.Entry<Integer,Listing>> all;
        synchronized(AUCTIONS){all=new ArrayList<>(AUCTIONS.entrySet());}
        int start=Math.max(0,page)*18;ItemStack[] icons=new ItemStack[27];
        for(int i=0;i<18&&start+i<all.size();i++){
            var entry=all.get(start+i);icons[i]=entry.getValue().stack().copy();
            icons[i].set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("Auction #"+entry.getKey()+" $"+entry.getValue().price()));
        }
        icons[18]=icon(Items.ARROW,"Previous page");icons[26]=icon(Items.ARROW,"Next page");
        icons[22]=icon(Items.GOLD_INGOT,"Use /ah sell <price> while holding a stack");
        menu(p,"Auction House Page "+(page+1),icons,(slot,b,type)->{
            if(slot==18){auctions(p,Math.max(0,page-1));return;}
            if(slot==26){auctions(p,page+1);return;}
            if(slot>=18||start+slot>=all.size())return;
            int key=all.get(start+slot).getKey();
            synchronized(AUCTIONS){
                Listing listing=AUCTIONS.get(key);
                if(listing==null){auctions(p,page);return;}
                if(listing.owner().equals(p.getUUID())){
                    if(p.getInventory().getFreeSlot()<0){message(p,"Make inventory room.");return;}
                    AUCTIONS.remove(key);p.getInventory().add(listing.stack().copy());message(p,"Cancelled.");
                }else{
                    if(p.getInventory().getFreeSlot()<0){message(p,"Make inventory room.");return;}
                    if(listing.price()>Long.MAX_VALUE-balance(listing.owner())){message(p,"Seller balance limit.");return;}
                    if(!charge(p.getUUID(),listing.price())){message(p,"Not enough money.");return;}
                    reward(listing.owner(),listing.price());AUCTIONS.remove(key);
                    p.getInventory().add(listing.stack().copy());message(p,"Auction purchased.");
                }
            }
            auctions(p,page);
        });
    }
    private static int createOrder(ServerPlayer p,String itemId,int count,int each){
        Item item=getItem(itemId);
        if(item==null||item==Items.AIR){message(p,"Unknown item id. Try minecraft:diamond");return 0;}
        long total=(long)count*each;
        if(!charge(p.getUUID(),total)){message(p,"Need $"+total);return 0;}
        synchronized(ORDERS){ORDERS.put(nextOrder++,new Order(p.getUUID(),itemId,count,each));}
        message(p,"Order created with $"+total+" escrow.");return 1;
    }
    private static void orders(ServerPlayer p,int page){
        List<Map.Entry<Integer,Order>> all;
        synchronized(ORDERS){all=new ArrayList<>(ORDERS.entrySet());}
        int start=page*18;ItemStack[] icons=new ItemStack[27];
        for(int i=0;i<18&&start+i<all.size();i++){
            var e=all.get(start+i);Item item=getItem(e.getValue().itemId());
            if(item!=null)icons[i]=icon(item,"Order #"+e.getKey()+": "+e.getValue().count()+" x $"+e.getValue().unitPrice());
        }
        icons[18]=icon(Items.ARROW,"Previous page");icons[26]=icon(Items.ARROW,"Next page");
        icons[22]=icon(Items.PAPER,"Create: /order create minecraft:diamond 10 100");
        menu(p,"Buy Orders Page "+(page+1),icons,(slot,b,t)->{
            if(slot==18){orders(p,Math.max(0,page-1));return;}
            if(slot==26){orders(p,page+1);return;}
            if(slot>=18||start+slot>=all.size())return;
            int key=all.get(start+slot).getKey();
            synchronized(ORDERS){
                Order order=ORDERS.get(key);
                if(order==null){orders(p,page);return;}
                long total=(long)order.count()*order.unitPrice();
                if(order.owner().equals(p.getUUID())){
                    if(total>Long.MAX_VALUE-balance(p.getUUID())){message(p,"Balance cannot hold refund.");return;}
                    ORDERS.remove(key);reward(p.getUUID(),total);message(p,"Order cancelled/refunded.");
                }else{
                    Item item=getItem(order.itemId());int available=0;
                    for(int i=0;i<p.getInventory().getContainerSize();i++)
                        if(p.getInventory().getItem(i).is(item))available+=p.getInventory().getItem(i).getCount();
                    if(available<order.count()){message(p,"You need "+order.count()+" of "+order.itemId());return;}
                    if(total>Long.MAX_VALUE-balance(p.getUUID())){message(p,"Balance limit.");return;}
                    ServerPlayer buyer=p.level().getServer().getPlayerList().getPlayer(order.owner());
                    if(buyer==null||buyer.getInventory().getFreeSlot()<0){message(p,"Buyer must be online with space.");return;}
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
    private static void rtpMenu(ServerPlayer p){
        ItemStack[] icons=new ItemStack[27];
        icons[11]=icon(Items.GRASS_BLOCK,"Random teleport near (up to 2000 blocks)");
        icons[15]=icon(Items.ENDER_PEARL,"Random teleport far (2000–8000 blocks)");
        icons[22]=icon(Items.BARRIER,"Cancel");
        menu(p,"Random Teleport",icons,(slot,b,type)->{
            if(slot==11)randomTeleport(p,false);
            if(slot==15)randomTeleport(p,true);
            if(slot==22)p.closeContainer();
        });
    }
    private static void randomTeleport(ServerPlayer p,boolean far){
        Random r=new Random();
        for(int attempt=0;attempt<20;attempt++){
            int radius=far?2000+r.nextInt(6001):200+r.nextInt(1801);
            double angle=r.nextDouble()*Math.PI*2;
            int x=(int)Math.round(Math.cos(angle)*radius),z=(int)Math.round(Math.sin(angle)*radius);
            BlockPos feet=p.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,64,z));
            if(feet.getY() < -60||feet.getY()>315)continue;
            if(!p.level().getBlockState(feet.below()).getFluidState().isEmpty())continue;
            if(!p.level().getBlockState(feet).isAir()||!p.level().getBlockState(feet.above()).isAir())continue;
            p.closeContainer();p.teleportTo(feet.getX()+0.5,feet.getY(),feet.getZ()+0.5);
            message(p,"Randomly teleported!");return;
        }
        message(p,"Couldn't find a safe destination. Try again.");
    }
    private static void queueMenu(ServerPlayer p){
        ItemStack[] icons=new ItemStack[27];
        icons[11]=icon(Items.EMERALD,"Join RTP queue (2 players)");
        icons[15]=icon(Items.BARRIER,"Leave queue");
        menu(p,"RTP Queue",icons,(slot,b,type)->{
            if(slot==11)joinQueue(p);
            if(slot==15)leaveQueue(p);
        });
    }
    private static synchronized void joinQueue(ServerPlayer p){
        if(queue==null||queue.equals(p.getUUID())){
            queue=p.getUUID();message(p,"Waiting for a second player. /rtpq leave to exit.");p.closeContainer();return;
        }
        ServerPlayer other=p.level().getServer().getPlayerList().getPlayer(queue);queue=null;
        if(other==null){queue=p.getUUID();message(p,"Waiting for second player.");p.closeContainer();return;}
        if(other.level()!=p.level()){message(p,"Partner is in another dimension. Join again.");return;}
        randomTeleport(p,false);other.teleportTo(p.getX()+1,p.getY(),p.getZ());
        message(other,"Your RTP queue matched with "+p.getName().getString());
    }
    private static synchronized int leaveQueue(ServerPlayer p){
        if(p.getUUID().equals(queue)){queue=null;message(p,"Left RTP queue.");}
        else message(p,"You are not in queue.");
        p.closeContainer();return 1;
    }
}
