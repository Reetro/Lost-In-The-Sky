package com.reetro.lostinthesky.world;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Wraps a chunk's biome resolver so sky island blocks get their sky biome. */
public final class SkyBiomeResolver {
    private SkyBiomeResolver() {
    }

    public static BiomeResolver wrap(BiomeResolver original, ChunkAccess chunk) {
        // A chunk doesn't know its dimension, but only the raised overworld is this tall.
        if (chunk.getMaxBuildHeight() - chunk.getMinBuildHeight() < SkyWorld.MIN_SKY_WORLD_HEIGHT) {
            return original;
        }
        SkyWorld sky = SkyWorld.get();
        if (sky == null || sky.layers().isEmpty()) {
            return original;
        }
        return (quartX, quartY, quartZ, sampler) -> {
            // Biomes are stored per 4x4x4 cell ("quart"); sample the middle of the cell.
            Holder<Biome> skyBiome = sky.skyBiomeAt((quartX << 2) + 2, (quartY << 2) + 2, (quartZ << 2) + 2);
            return skyBiome != null ? skyBiome : original.getNoiseBiome(quartX, quartY, quartZ, sampler);
        };
    }
}
