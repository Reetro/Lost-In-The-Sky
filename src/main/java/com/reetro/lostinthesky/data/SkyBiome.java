package com.reetro.lostinthesky.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import java.util.List;

/**
 * A biome that islands in one layer can have. Loaded from
 * {@code data/<namespace>/lost_in_the_sky/sky_biome/<name>.json}.
 *
 * <p>Any biome works: vanilla, another mod's, or your own from {@code worldgen/biome}. The island's
 * blocks are given the biome, so its sky colour, fog, grass colour and mob spawns all apply.
 *
 * @param layer       id of the sky_layer this biome belongs to
 * @param biome       the biome to paint onto the island
 * @param weight      how often it is picked compared to the layer's other sky biomes
 * @param top         surface block
 * @param filler      the few blocks under the surface
 * @param core        everything else
 * @param fillerDepth how many filler blocks sit under the surface
 * @param decorations configured features (trees, flowers, ores...) placed on the island's surface
 */
public record SkyBiome(ResourceLocation layer, ResourceKey<Biome> biome, int weight, BlockState top,
                       BlockState filler, BlockState core, int fillerDepth, List<Decoration> decorations) {

    public static final Codec<SkyBiome> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("layer").forGetter(SkyBiome::layer),
            ResourceKey.codec(Registries.BIOME).fieldOf("biome").forGetter(SkyBiome::biome),
            Codec.intRange(1, 10000).optionalFieldOf("weight", 1).forGetter(SkyBiome::weight),
            BlockState.CODEC.optionalFieldOf("top", Blocks.GRASS_BLOCK.defaultBlockState()).forGetter(SkyBiome::top),
            BlockState.CODEC.optionalFieldOf("filler", Blocks.DIRT.defaultBlockState()).forGetter(SkyBiome::filler),
            BlockState.CODEC.optionalFieldOf("core", Blocks.STONE.defaultBlockState()).forGetter(SkyBiome::core),
            Codec.intRange(0, 32).optionalFieldOf("filler_depth", 3).forGetter(SkyBiome::fillerDepth),
            Decoration.CODEC.listOf().optionalFieldOf("decorations", List.of()).forGetter(SkyBiome::decorations)
    ).apply(i, SkyBiome::new));

    /**
     * @param feature configured feature to place, e.g. {@code minecraft:oak}
     * @param tries   attempts per chunk
     * @param chance  chance that each attempt places the feature
     */
    public record Decoration(ResourceKey<ConfiguredFeature<?, ?>> feature, int tries, float chance) {
        public static final Codec<Decoration> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKey.codec(Registries.CONFIGURED_FEATURE).fieldOf("feature").forGetter(Decoration::feature),
                Codec.intRange(1, 256).optionalFieldOf("tries", 1).forGetter(Decoration::tries),
                Codec.floatRange(0f, 1f).optionalFieldOf("chance", 1f).forGetter(Decoration::chance)
        ).apply(i, Decoration::new));
    }
}
