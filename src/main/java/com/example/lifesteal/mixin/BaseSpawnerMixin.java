package com.example.lifesteal.mixin;

import com.example.lifesteal.LifestealSpawners;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not spawn actual mobs from managed Lifesteal virtual generators. */
@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerMixin {
    @Inject(method="serverTick",at=@At("HEAD"),cancellable=true)
    private void lifesteal$virtualSpawner(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        if (LifestealSpawners.isManaged(level,pos)) ci.cancel();
    }
}
