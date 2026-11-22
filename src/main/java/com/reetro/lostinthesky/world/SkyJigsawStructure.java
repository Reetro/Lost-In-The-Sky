package com.reetro.lostinthesky.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.reetro.lostinthesky.LostInTheSky;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

import java.util.Optional;

/**
 * A jigsaw structure that sits on top of a sky island instead of the ground.
 *
 * <p>Works like {@code minecraft:jigsaw}, plus a {@code layer} field. The structure only spawns
 * where that layer has an island under the chunk's centre, and starts on the island's surface.
 * Point {@code start_pool} at any mod's template pool (When Dungeons Arise, Integrated Dungeons...)
 * to lift its dungeons into the sky.
 */
public class SkyJigsawStructure extends Structure {
    public static final MapCodec<SkyJigsawStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i),
            ResourceLocation.CODEC.fieldOf("layer").forGetter(s -> s.layer),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool),
            ResourceLocation.CODEC.optionalFieldOf("start_jigsaw_name").forGetter(s -> s.startJigsawName),
            Codec.intRange(0, 20).fieldOf("size").forGetter(s -> s.size),
            Codec.INT.optionalFieldOf("y_offset", 1).forGetter(s -> s.yOffset),
            Codec.intRange(1, 128).optionalFieldOf("max_distance_from_center", 80).forGetter(s -> s.maxDistanceFromCenter),
            Codec.BOOL.optionalFieldOf("use_expansion_hack", false).forGetter(s -> s.useExpansionHack)
    ).apply(i, SkyJigsawStructure::new));

    private final ResourceLocation layer;
    private final Holder<StructureTemplatePool> startPool;
    private final Optional<ResourceLocation> startJigsawName;
    private final int size;
    private final int yOffset;
    private final int maxDistanceFromCenter;
    private final boolean useExpansionHack;

    public SkyJigsawStructure(StructureSettings settings, ResourceLocation layer, Holder<StructureTemplatePool> startPool,
                              Optional<ResourceLocation> startJigsawName, int size, int yOffset,
                              int maxDistanceFromCenter, boolean useExpansionHack) {
        super(settings);
        this.layer = layer;
        this.startPool = startPool;
        this.startJigsawName = startJigsawName;
        this.size = size;
        this.yOffset = yOffset;
        this.maxDistanceFromCenter = maxDistanceFromCenter;
        this.useExpansionHack = useExpansionHack;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        if (context.heightAccessor().getHeight() < SkyWorld.MIN_SKY_WORLD_HEIGHT) {
            return Optional.empty();
        }
        SkyWorld sky = SkyWorld.get();
        if (sky == null) {
            return Optional.empty();
        }
        SkyWorld.CompiledLayer skyLayer = sky.layer(layer);
        if (skyLayer == null) {
            LostInTheSky.LOGGER.warn("sky_jigsaw structure uses unknown layer {}", layer);
            return Optional.empty();
        }
        ChunkPos chunk = context.chunkPos();
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        Integer surface = sky.surfaceAt(skyLayer, x, z);
        if (surface == null) {
            return Optional.empty();
        }
        BlockPos start = new BlockPos(x, surface + yOffset, z);
        return JigsawPlacement.addPieces(context, startPool, startJigsawName, size, start, useExpansionHack,
                Optional.empty(), maxDistanceFromCenter, PoolAliasLookup.EMPTY, DimensionPadding.ZERO,
                LiquidSettings.APPLY_WATERLOGGING);
    }

    @Override
    public StructureType<?> type() {
        return LostInTheSky.SKY_JIGSAW.get();
    }
}
