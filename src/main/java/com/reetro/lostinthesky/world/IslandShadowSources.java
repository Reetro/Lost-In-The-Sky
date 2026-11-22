package com.reetro.lostinthesky.world;

import org.jetbrains.annotations.Nullable;

/**
 * Added to {@link net.minecraft.world.level.lighting.ChunkSkyLightSources} by
 * {@link com.reetro.lostinthesky.mixin.ChunkSkyLightSourcesMixin}. Holds, per column of the chunk,
 * the lowest island underside Y ({@link IslandShadows#NO_ISLAND} for open sky). Blocks at or above
 * that Y don't block sky light, so islands cast no shadow on the ground below.
 */
public interface IslandShadowSources {

    /** 256 entries indexed {@code x + z * 16} (chunk-local), or null when no island is over this chunk. */
    @Nullable
    int[] lostinthesky$getIslandBottoms();

    void lostinthesky$setIslandBottoms(@Nullable int[] bottoms);
}
