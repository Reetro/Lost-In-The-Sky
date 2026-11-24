package com.reetro.lostinthesky.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

import java.util.List;

/**
 * A cave biome for the inside of a layer's cave islands, e.g. {@code minecraft:lush_caves}. Loaded
 * from {@code data/<namespace>/lost_in_the_sky/sky_cave_biome/<name>.json}.
 *
 * <p>Every cave island in the layer picks one cave biome by weight (layers with none keep plain
 * stone caves). The biome is painted onto the island's interior, more than a few blocks under the
 * surface, so the surface keeps its sky biome. Its decorations are placed on the cave floors,
 * ceilings or in the cave air.
 *
 * @param layer       id of the sky_layer whose cave islands can get this biome
 * @param biome       the biome to paint inside the island
 * @param weight      how often it is picked compared to the layer's other cave biomes
 * @param decorations configured features placed in the caves
 */
public record SkyCaveBiome(ResourceLocation layer, ResourceKey<Biome> biome, int weight, List<Decoration> decorations) {

    public static final Codec<SkyCaveBiome> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("layer").forGetter(SkyCaveBiome::layer),
            ResourceKey.codec(Registries.BIOME).fieldOf("biome").forGetter(SkyCaveBiome::biome),
            Codec.intRange(1, 10000).optionalFieldOf("weight", 1).forGetter(SkyCaveBiome::weight),
            Decoration.CODEC.listOf().optionalFieldOf("decorations", List.of()).forGetter(SkyCaveBiome::decorations)
    ).apply(i, SkyCaveBiome::new));

    /**
     * @param feature   configured feature to place, e.g. {@code minecraft:moss_patch}
     * @param placement where in the cave: on the floor, under the ceiling, or anywhere in the cave air
     * @param tries     attempts per chunk (each picks a random column, so caves that fill more of the
     *                  chunk get more)
     * @param chance    chance that each attempt places the feature
     */
    public record Decoration(ResourceKey<ConfiguredFeature<?, ?>> feature, Placement placement, int tries, float chance) {
        public static final Codec<Decoration> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKey.codec(Registries.CONFIGURED_FEATURE).fieldOf("feature").forGetter(Decoration::feature),
                Placement.CODEC.optionalFieldOf("placement", Placement.FLOOR).forGetter(Decoration::placement),
                Codec.intRange(1, 256).optionalFieldOf("tries", 1).forGetter(Decoration::tries),
                Codec.floatRange(0f, 1f).optionalFieldOf("chance", 1f).forGetter(Decoration::chance)
        ).apply(i, Decoration::new));
    }

    public enum Placement implements StringRepresentable {
        /** Cave air with rock directly below it. */
        FLOOR("floor"),
        /** Cave air with rock directly above it. */
        CEILING("ceiling"),
        /** Any cave air (for features that search for surfaces themselves, like dripstone clusters). */
        AIR("air");

        public static final Codec<Placement> CODEC = StringRepresentable.fromEnum(Placement::values);

        private final String name;

        Placement(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
