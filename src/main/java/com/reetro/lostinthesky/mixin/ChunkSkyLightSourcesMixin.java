package com.reetro.lostinthesky.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.reetro.lostinthesky.world.IslandShadowSources;
import com.reetro.lostinthesky.world.IslandShadows;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops sky islands from casting shadows on the ground.
 *
 * <p>Vanilla gives each block column one sky light "source" Y: the first edge, going down from the
 * top, where light can't pass. Everything at or above it gets full sky light, and below it light
 * only creeps in sideways. An island over the column moves that point up to the island top, which
 * leaves the ground under any island wider than ~30 blocks pitch black.
 *
 * <p>Here, edges at or above the column's lowest island underside never block, so the source drops
 * to the real ground. The trade-off: everything above the ground in those columns (island caves,
 * roofed builds on islands) counts as open sky.
 *
 * <p>The underside per column is stored on the sources object. The server computes it in
 * {@code fillFrom}. The client gets it from the server (see {@link IslandShadows}), so client-side
 * relighting after block changes agrees with the server.
 */
@Mixin(ChunkSkyLightSources.class)
public abstract class ChunkSkyLightSourcesMixin implements IslandShadowSources {

    @Unique
    @Nullable
    private volatile int[] lostinthesky$islandBottoms;

    @Inject(method = "fillFrom", at = @At("HEAD"))
    private void lostinthesky$loadIslandBottoms(ChunkAccess chunk, CallbackInfo ci) {
        this.lostinthesky$islandBottoms = IslandShadows.bottomsFor(chunk);
    }

    @WrapOperation(
            method = {"findLowestSourceY", "updateEdge", "findLowestSourceBelow"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/ChunkSkyLightSources;isEdgeOccluded(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"),
            require = 3)
    private boolean lostinthesky$islandsDontOcclude(BlockGetter level, BlockPos upperPos, BlockState upperState,
                                                     BlockPos lowerPos, BlockState lowerState,
                                                     Operation<Boolean> original) {
        int[] bottoms = this.lostinthesky$islandBottoms;
        // Positions here are chunk-local in vanilla, but mask anyway.
        if (bottoms != null && lowerPos.getY() >= bottoms[(lowerPos.getX() & 15) + (lowerPos.getZ() & 15) * 16]) {
            return false;
        }
        return original.call(level, upperPos, upperState, lowerPos, lowerState);
    }

    @Override
    @Nullable
    public int[] lostinthesky$getIslandBottoms() {
        return this.lostinthesky$islandBottoms;
    }

    @Override
    public void lostinthesky$setIslandBottoms(@Nullable int[] bottoms) {
        this.lostinthesky$islandBottoms = bottoms;
    }
}
