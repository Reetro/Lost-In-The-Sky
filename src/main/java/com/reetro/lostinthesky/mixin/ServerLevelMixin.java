package com.reetro.lostinthesky.mixin;

import com.mojang.datafixers.util.Pair;
import com.reetro.lostinthesky.world.SkyWorld;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code /locate biome} (and anything else using {@link ServerLevel#findClosestBiome3d}) find
 * sky island biomes. Vanilla only asks the generator's biome source, which never contains them:
 * island biomes are painted onto chunks afterwards ({@link ChunkAccessMixin}).
 *
 * <p>Sky-only biomes (e.g. {@code lost_in_the_sky:high_sky_islands}) are answered from the island
 * grid alone, which is fast. If the biome (or tag) can also be on the ground, both searches run and
 * the nearer result wins.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "findClosestBiome3d", at = @At("HEAD"), cancellable = true)
    private void lostinthesky$findSkyBiomes(Predicate<Holder<Biome>> biomes, BlockPos pos, int radius,
                                            int horizontalStep, int verticalStep,
                                            CallbackInfoReturnable<Pair<BlockPos, Holder<Biome>>> cir) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (level.dimension() != Level.OVERWORLD || level.getHeight() < SkyWorld.MIN_SKY_WORLD_HEIGHT) {
            return;
        }
        SkyWorld sky = SkyWorld.get();
        if (sky == null) {
            return;
        }
        Pair<BlockPos, Holder<Biome>> island = sky.findClosestIsland(biomes, pos, radius);
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        if (source.possibleBiomes().stream().noneMatch(biomes)) {
            // Only exists on islands: skip vanilla's slow search (null = "not found").
            cir.setReturnValue(island);
            return;
        }
        if (island == null) {
            return;
        }
        Pair<BlockPos, Holder<Biome>> ground = source.findClosestBiome3d(pos, radius, horizontalStep, verticalStep,
                biomes, level.getChunkSource().randomState().sampler(), level);
        cir.setReturnValue(ground == null || horizontalDistSqr(pos, island.getFirst()) < horizontalDistSqr(pos, ground.getFirst())
                ? island : ground);
    }

    private static long horizontalDistSqr(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
