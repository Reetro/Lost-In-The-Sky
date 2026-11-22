package com.reetro.lostinthesky.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.reetro.lostinthesky.world.SkyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps players from spawning on sky islands. Vanilla picks a spawn by taking the heightmap (the
 * highest solid block) and scanning down from there, which lands on an island top whenever one
 * hangs over the column. {@code getOverworldRespawnPos} is used for the world spawn, a player's
 * first join and every respawn without a bed, so fixing it here covers all three.
 *
 * <p>Island heightmap values are capped to just below the lowest island underside, so the scan
 * starts in the air gap and finds the ground. Any result that would still put the player in or on
 * an island is rejected, and vanilla moves on to the next column.
 */
@Mixin(PlayerRespawnLogic.class)
public abstract class PlayerRespawnLogicMixin {

    @WrapOperation(
            method = "getOverworldRespawnPos",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private static int lostinthesky$heightBelowIslands(LevelChunk chunk, Heightmap.Types type, int localX, int localZ,
                                                      Operation<Integer> original) {
        int height = original.call(chunk, type, localX, localZ);
        Integer bottom = lostinthesky$islandBottom(chunk.getLevel(),
                chunk.getPos().getMinBlockX() + localX, chunk.getPos().getMinBlockZ() + localZ);
        // Heightmaps hold "first free Y". Capping at bottom - 2 makes the scan start at bottom - 1.
        return bottom == null ? height : Math.min(height, bottom - 2);
    }

    @Inject(method = "getOverworldRespawnPos", at = @At("RETURN"), cancellable = true)
    private static void lostinthesky$rejectIslandSpawns(ServerLevel level, int x, int z,
                                                        CallbackInfoReturnable<BlockPos> cir) {
        BlockPos pos = cir.getReturnValue();
        if (pos == null) {
            return;
        }
        Integer bottom = lostinthesky$islandBottom(level, x, z);
        // Feet at y, head at y + 1: both must be below the island.
        if (bottom != null && pos.getY() + 1 >= bottom) {
            cir.setReturnValue(null);
        }
    }

    @Nullable
    private static Integer lostinthesky$islandBottom(Level level, int x, int z) {
        if (level.dimension() != Level.OVERWORLD || level.getHeight() < SkyWorld.MIN_SKY_WORLD_HEIGHT) {
            return null;
        }
        SkyWorld sky = SkyWorld.get();
        return sky == null ? null : sky.lowestIslandBottomAt(x, z);
    }
}
