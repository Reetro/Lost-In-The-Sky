package com.reetro.lostinthesky.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.reetro.lostinthesky.world.SkyStructureContext;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a sky structure is being laid out ({@link SkyStructureContext}), terrain-following jigsaw
 * pieces (village streets, and the houses attached to them) get their height from the island's
 * surface instead of the generator's ground height. Islands are placed later, as a feature, so
 * the generator doesn't know about them.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getFirstFreeHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I"),
            require = 2)
    private int lostinthesky$islandHeight(ChunkGenerator generator, int x, int z, Heightmap.Types type,
                                          LevelHeightAccessor level, RandomState random, Operation<Integer> original) {
        SkyStructureContext.Active sky = SkyStructureContext.get();
        return sky == null ? original.call(generator, x, z, type, level, random) : sky.surfaceAt(x, z);
    }
}
