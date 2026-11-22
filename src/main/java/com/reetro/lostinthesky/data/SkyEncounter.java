package com.reetro.lostinthesky.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * A small structure and/or boss placed on the centre of an island. Loaded from
 * {@code data/<namespace>/lost_in_the_sky/sky_encounter/<name>.json}.
 *
 * <p>Use this for boss arenas and small dungeons (templates up to 32x32 blocks). For big dungeons,
 * use the {@code lost_in_the_sky:sky_jigsaw} structure type instead.
 *
 * @param layer     id of the sky_layer this encounter appears in
 * @param chance    chance per island (0-1). One encounter at most per island
 * @param skyBiomes if not empty, only islands with one of these sky_biome ids get it
 * @param template  optional structure template (.nbt) placed on the island, centred
 * @param yOffset   blocks above the island surface to place the template / entities
 * @param entities  mobs or bosses to spawn, e.g. {@code cataclysm:ignis}. Missing mods are skipped
 */
public record SkyEncounter(ResourceLocation layer, float chance, List<ResourceLocation> skyBiomes,
                           Optional<ResourceLocation> template, int yOffset, List<EntityEntry> entities) {

    public static final Codec<SkyEncounter> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("layer").forGetter(SkyEncounter::layer),
            Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(SkyEncounter::chance),
            ResourceLocation.CODEC.listOf().optionalFieldOf("sky_biomes", List.of()).forGetter(SkyEncounter::skyBiomes),
            ResourceLocation.CODEC.optionalFieldOf("template").forGetter(SkyEncounter::template),
            Codec.INT.optionalFieldOf("y_offset", 1).forGetter(SkyEncounter::yOffset),
            EntityEntry.CODEC.listOf().optionalFieldOf("entities", List.of()).forGetter(SkyEncounter::entities)
    ).apply(i, SkyEncounter::new));

    /**
     * @param type       entity id
     * @param offset     position relative to the encounter origin
     * @param count      how many to spawn
     * @param persistent if true the mob never despawns (use for bosses)
     */
    public record EntityEntry(ResourceLocation type, BlockPos offset, int count, boolean persistent) {
        public static final Codec<EntityEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("type").forGetter(EntityEntry::type),
                BlockPos.CODEC.optionalFieldOf("offset", BlockPos.ZERO).forGetter(EntityEntry::offset),
                Codec.intRange(1, 32).optionalFieldOf("count", 1).forGetter(EntityEntry::count),
                Codec.BOOL.optionalFieldOf("persistent", true).forGetter(EntityEntry::persistent)
        ).apply(i, EntityEntry::new));
    }
}
