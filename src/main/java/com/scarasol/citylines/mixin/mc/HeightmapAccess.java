package com.scarasol.citylines.mixin.mc;

import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Heightmap.class)
public interface HeightmapAccess {
    @Invoker("setHeight")
    void citylines$setHeight(int x, int z, int firstFreeY);
}
