package com.example.diddybludpaper;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read and update offline Minecraft playerdata in-place.
 * Only the selected inventory list is modified; unrelated NBT is preserved.
 * Requires the target to remain offline throughout the edit.
 */
final class OfflineInventoryData {
    private final JavaPlugin plugin;
    OfflineInventoryData(JavaPlugin plugin){this.plugin=plugin;}

    private Path playerFile(OfflinePlayer player){
        // The overworld owns all players' data, regardless of the dimension they left in.
        return Bukkit.getWorlds().getFirst().getWorldFolder().toPath()
            .resolve("playerdata").resolve(player.getUniqueId()+".dat");
    }
    private static String tagName(boolean ender){return ender?"EnderItems":"Inventory";}
    private static int toGui(int slot,boolean ender){
        if(ender)return slot>=0&&slot<27?slot:-1;
        if(slot>=0&&slot<36)return slot;
        if(slot>=100&&slot<=103)return 36+slot-100;
        return slot==150?40:-1; // offhand: Slot -106b == unsigned 150
    }
    private static int toNbt(int guiSlot,boolean ender){
        if(ender)return guiSlot;
        if(guiSlot<36)return guiSlot;
        if(guiSlot<40)return 100+guiSlot-36;
        return 150;
    }
    private static CompoundBinaryTag read(Path path) throws IOException {
        return BinaryTagIO.reader().read(path,BinaryTagIO.Compression.GZIP);
    }
    static final class Snapshot {
        final Path path;
        final long modified;
        final ItemStack[] items;
        Snapshot(Path path,long modified,ItemStack[] items){
            this.path=path;this.modified=modified;this.items=items;
        }
    }
    Snapshot load(OfflinePlayer target,boolean ender) throws IOException {
        if(target.isOnline())throw new IOException("Player is online.");
        Path path=playerFile(target);
        if(!Files.isRegularFile(path))throw new IOException("No saved player data is available.");
        CompoundBinaryTag data=read(path);
        int dataVersion=data.getInt("DataVersion",0);
        if(dataVersion==0)throw new IOException("Player file has no DataVersion.");
        ItemStack[] items=new ItemStack[ender?27:41];
        ListBinaryTag list=data.getList(tagName(ender));
        for(var raw:list){
            if(!(raw instanceof CompoundBinaryTag item))continue;
            int slot=item.getByte("Slot")&255;
            int gui=toGui(slot,ender);
            if(gui<0)continue;
            try{
                // Paper's item API deserializes the current components NBT and
                // performs server-side data conversions where needed.
                CompoundBinaryTag withVersion=item.put("DataVersion",IntBinaryTag.intBinaryTag(dataVersion))
                    .remove("Slot");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                BinaryTagIO.writer().write(withVersion,out);
                items[gui]=ItemStack.deserializeBytes(out.toByteArray());
            }catch(RuntimeException bad){
                throw new IOException("Unable to read saved item in slot "+slot+
                    ". No inventory changes have been made.",bad);
            }
        }
        return new Snapshot(path,Files.getLastModifiedTime(path).toMillis(),items);
    }
    void save(OfflinePlayer target,boolean ender,Snapshot snapshot,ItemStack[] updated)
        throws IOException {
        if(target.isOnline())throw new IOException("Player is now online. Changes were not saved.");
        if(!Files.isRegularFile(snapshot.path)||
            Files.getLastModifiedTime(snapshot.path).toMillis()!=snapshot.modified)
            throw new IOException("Player data changed while editing. Changes were not saved.");
        CompoundBinaryTag root=read(snapshot.path);
        String selected=tagName(ender);
        List<CompoundBinaryTag> entries=new ArrayList<>();
        // Preserve unrecognized/extra slot records owned by Minecraft or other plugins.
        for(var raw:root.getList(selected)){
            if(!(raw instanceof CompoundBinaryTag item))continue;
            int nbtSlot=item.getByte("Slot")&255;
            if(toGui(nbtSlot,ender)<0)entries.add(item);
        }
        for(int slot=0;slot<updated.length;slot++){
            ItemStack stack=updated[slot];
            if(stack==null||stack.getType().isAir()||stack.getAmount()<=0)continue;
            try{
                byte[] bytes=stack.serializeAsBytes();
                CompoundBinaryTag serialized=BinaryTagIO.reader().read(
                    new ByteArrayInputStream(bytes));
                CompoundBinaryTag asSaved=serialized.remove("DataVersion")
                    .put("Slot",ByteBinaryTag.byteBinaryTag((byte)toNbt(slot,ender)));
                if(!asSaved.contains("id"))throw new IOException(
                    "Item serialization did not contain an item id in slot "+slot);
                entries.add(asSaved);
            }catch(RuntimeException bad){
                throw new IOException("Cannot serialize an item in slot "+slot+
                    ". No changes saved.",bad);
            }
        }
        CompoundBinaryTag changed=root.put(selected,ListBinaryTag.from(entries));
        Path backup=snapshot.path.resolveSibling(snapshot.path.getFileName()+".diddyblud.bak");
        Path temp=Files.createTempFile(snapshot.path.getParent(),"diddyblud-offline-",".dat");
        try{
            // Always keep the original player file before replacing it.
            Files.copy(snapshot.path,backup,StandardCopyOption.REPLACE_EXISTING);
            BinaryTagIO.writer().write(changed,temp,BinaryTagIO.Compression.GZIP);
            if(target.isOnline()||
                Files.getLastModifiedTime(snapshot.path).toMillis()!=snapshot.modified)
                throw new IOException("Player logged in or data changed; save cancelled.");
            try{
                Files.move(temp,snapshot.path,StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }catch(java.nio.file.AtomicMoveNotSupportedException unsupported){
                Files.move(temp,snapshot.path,StandardCopyOption.REPLACE_EXISTING);
            }
        }finally{
            Files.deleteIfExists(temp);
        }
    }
}
