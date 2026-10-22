package com.example.lifesteal;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Server-only vanilla mob spawners with type-preserving items and stacked placement.
 *
 * Spawning itself uses Minecraft's native spawner mechanics. A stack is a
 * single functioning spawner block with a tracked count; the count currently
 * does NOT multiply its vanilla spawn rate.
 */
public final class LifestealSpawners {
    private LifestealSpawners() {}
    private static final String[] MOBS = {
        "zombie", "skeleton", "spider", "creeper", "enderman",
        "blaze", "pig", "cow", "sheep", "chicken", "iron_golem",
        "witch", "slime", "magma_cube", "husk", "stray", "drowned",
        "cave_spider", "silverfish", "endermite", "guardian", "shulker",
        "pillager", "vindicator", "evoker", "wither_skeleton",
        "zombified_piglin", "piglin", "hoglin", "zoglin",
        "rabbit", "wolf", "cat", "fox", "frog", "bee", "villager"
    };
    private record Entry(String mob, int count) {}
    private static final Map<String,Entry> PLACED = new HashMap<>();
    private static MinecraftServer activeServer;

    private static String key(ServerLevel level,BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX()+","+pos.getY()+","+pos.getZ();
    }
    public static ItemStack spawner(String mob,int count) {
        if(!validMob(mob))throw new IllegalArgumentException("Invalid mob: "+mob);
        ItemStack stack=new ItemStack(Items.SPAWNER,Math.max(1,Math.min(count,64)));
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(niceName(mob)+" Spawner"));
        CustomData.update(DataComponents.CUSTOM_DATA,stack,
            tag->tag.putBoolean("lifesteal_spawner_"+mob,true));
        return stack;
    }
    private static String niceName(String mob) {
        String[] parts=mob.split("_");
        StringBuilder b=new StringBuilder();
        for(String word:parts) {
            if(!b.isEmpty())b.append(' ');
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return b.toString();
    }
    public static boolean validMob(String mob) {
        for(String candidate:MOBS)if(candidate.equals(mob))return true;
        return false;
    }
    public static String mobFromItem(ItemStack item) {
        if(item.isEmpty()||!item.is(Items.SPAWNER))return null;
        var tag=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        for(String mob:MOBS)if(tag.contains("lifesteal_spawner_"+mob))return mob;
        return null;
    }
    public static String[] mobs(){return MOBS.clone();}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            activeServer=server;load(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            save();activeServer=null;PLACED.clear();
        });
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if(!(player instanceof ServerPlayer p)||!(level instanceof ServerLevel sl))
                return InteractionResult.PASS;
            BlockPos clicked=hit.getBlockPos();
            String existingKey=key(sl,clicked);
            Entry existing=PLACED.get(existingKey);
            ItemStack held=p.getItemInHand(hand);
            String mob=mobFromItem(held);
            // A sneaking empty-handed click opens the management chest GUI.
            if(existing!=null&&held.isEmpty()){
                SmpMenus.openSpawnerManager(p,sl,clicked,existing.mob(),existing.count());
                return InteractionResult.SUCCESS;
            }
            if(mob==null)return InteractionResult.PASS;
            if(existing!=null) {
                if(!existing.mob().equals(mob)){
                    p.sendSystemMessage(Component.literal("Only matching mob spawners can stack."));
                    return InteractionResult.FAIL;
                }
                int space=10000-existing.count();
                if(space<=0){p.sendSystemMessage(Component.literal("Spawner stack is full."));return InteractionResult.FAIL;}
                int adding=Math.min(space,p.isShiftKeyDown()?held.getCount():1);
                if(!p.getAbilities().instabuild)held.shrink(adding);
                PLACED.put(existingKey,new Entry(mob,existing.count()+adding));
                save();
                p.sendSystemMessage(Component.literal(niceName(mob)+" Spawners: "+(existing.count()+adding)));
                return InteractionResult.SUCCESS;
            }
            // Explicit server-side placement avoids operator-only block_entity_data.
            BlockPos target=level.getBlockState(clicked).canBeReplaced()?clicked:clicked.relative(hit.getDirection());
            if(!level.getBlockState(target).canBeReplaced())return InteractionResult.PASS;
            if(!level.mayInteract(player,target))return InteractionResult.FAIL;
            if(!sl.setBlock(target,Blocks.SPAWNER.defaultBlockState(),3))return InteractionResult.FAIL;
            if(!(sl.getBlockEntity(target) instanceof SpawnerBlockEntity blockEntity)){
                sl.removeBlock(target,false);
                return InteractionResult.FAIL;
            }
            EntityType<?> entity=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft",mob));
            if(entity==null){
                sl.removeBlock(target,false);
                return InteractionResult.FAIL;
            }
            blockEntity.setEntityId(entity,sl.getRandom());
            blockEntity.setChanged();
            int placing=p.isShiftKeyDown()?held.getCount():1;
            if(!p.getAbilities().instabuild)held.shrink(placing);
            PLACED.put(key(sl,target),new Entry(mob,placing));
            save();
            p.sendSystemMessage(Component.literal("Placed "+placing+" "+niceName(mob)+" spawner(s)."));
            return InteractionResult.SUCCESS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level,player,pos,state,blockEntity)->{
            if(!(player instanceof ServerPlayer p)||!(level instanceof ServerLevel sl))return true;
            String k=key(sl,pos);
            Entry entry=PLACED.get(k);
            if(entry==null)return true;
            if(!state.is(Blocks.SPAWNER)){PLACED.remove(k);save();return true;}
            if(!level.mayInteract(player,pos))return false;
            if(!sl.removeBlock(pos,false))return false;
            PLACED.remove(k);
            save();
            int remaining=entry.count();
            if(!p.getAbilities().instabuild){
                while(remaining>0){
                    int amount=Math.min(64,remaining);
                    ItemStack drops=spawner(entry.mob(),amount);
                    if(!p.getInventory().add(drops))p.drop(drops,false);
                    remaining-=amount;
                }
            }
            p.sendSystemMessage(Component.literal("Recovered "+entry.count()+" "+niceName(entry.mob())+" spawner(s)."));
            return false;
        });
    }
    public static int count(ServerLevel level,BlockPos pos){
        Entry e=PLACED.get(key(level,pos));return e==null?0:e.count();
    }
    public static String mob(ServerLevel level,BlockPos pos){
        Entry e=PLACED.get(key(level,pos));return e==null?null:e.mob();
    }
    public static boolean removeOne(ServerPlayer p,ServerLevel level,BlockPos pos){
        if(!level.getBlockState(pos).is(Blocks.SPAWNER))return false;
        String k=key(level,pos);Entry e=PLACED.get(k);
        if(e==null||e.count()<2||p.getInventory().getFreeSlot()<0)return false;
        p.getInventory().add(spawner(e.mob(),1));
        PLACED.put(k,new Entry(e.mob(),e.count()-1));
        save();return true;
    }
    private static Path file(MinecraftServer server){
        return server.getWorldPath(LevelResource.ROOT).resolve("lifesteal-spawners.properties");
    }
    private static void load(MinecraftServer server){
        PLACED.clear();
        Path path=file(server);
        if(!Files.exists(path))return;
        Properties properties=new Properties();
        try(InputStream in=Files.newInputStream(path)){
            properties.load(in);
            for(String k:properties.stringPropertyNames()){
                String[] parts=properties.getProperty(k).split(",",2);
                if(parts.length!=2||!validMob(parts[0]))continue;
                int count=Integer.parseInt(parts[1]);
                if(count>=1&&count<=10000)PLACED.put(k,new Entry(parts[0],count));
            }
        }catch(Exception ex){
            System.err.println("[Lifesteal] Failed loading spawners: "+ex.getMessage());
        }
    }
    private static void save(){
        if(activeServer==null)return;
        Properties properties=new Properties();
        for(var e:PLACED.entrySet())
            properties.setProperty(e.getKey(),e.getValue().mob()+","+e.getValue().count());
        try{
            Path destination=file(activeServer);
            Files.createDirectories(destination.getParent());
            Path temp=destination.resolveSibling(destination.getFileName()+".tmp");
            try(OutputStream out=Files.newOutputStream(temp)){
                properties.store(out,"Lifesteal placed spawners - do not edit while server runs");
            }
            try{
                Files.move(temp,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }catch(java.nio.file.AtomicMoveNotSupportedException e){
                Files.move(temp,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }catch(Exception ex){
            System.err.println("[Lifesteal] FAILED to save spawner state: "+ex.getMessage());
        }
    }
}
