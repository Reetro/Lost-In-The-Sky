package com.reetro.lostinthesky.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.reetro.lostinthesky.LostInTheSky;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

import java.util.List;
import java.util.Optional;

/**
 * A jigsaw structure that sits on top of a sky island instead of the ground.
 *
 * <p>Works like {@code minecraft:jigsaw}, plus a {@code layer} field. The structure only spawns
 * where that layer has an island under the chunk's centre, and starts on the island's surface.
 * Point {@code start_pool} at any mod's template pool (When Dungeons Arise, Integrated Dungeons...)
 * to lift its dungeons into the sky.
 *
 * <p>With {@code island_center}, it starts at the centre of an island instead, on islands at least
 * {@code min_island_radius} big, each with {@code island_chance}, so spread-out structures like
 * villages stay on the island. Use it with a structure set of spacing 1 / separation 0: only the
 * chunk holding the island's centre can start it.
 * Terrain-following pieces (village streets) follow the island surface: see
 * {@link SkyStructureContext}. Pieces that would hang off the island's edge are dropped, and the
 * island is shaped to fit under the buildings when it generates (see {@code SkyIslandFeature}).
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
            Codec.BOOL.optionalFieldOf("use_expansion_hack", false).forGetter(s -> s.useExpansionHack),
            Codec.BOOL.optionalFieldOf("island_center", false).forGetter(s -> s.islandCenter),
            Codec.intRange(0, 512).optionalFieldOf("min_island_radius", 0).forGetter(s -> s.minIslandRadius),
            Codec.floatRange(0f, 1f).optionalFieldOf("island_chance", 1f).forGetter(s -> s.islandChance)
    ).apply(i, SkyJigsawStructure::new));

    private final ResourceLocation layer;
    private final Holder<StructureTemplatePool> startPool;
    private final Optional<ResourceLocation> startJigsawName;
    private final int size;
    private final int yOffset;
    private final int maxDistanceFromCenter;
    private final boolean useExpansionHack;
    private final boolean islandCenter;
    private final int minIslandRadius;
    private final float islandChance;
    /** Makes each structure's per-island roll independent of other structures'. */
    private final long chanceSalt;

    public SkyJigsawStructure(StructureSettings settings, ResourceLocation layer, Holder<StructureTemplatePool> startPool,
                              Optional<ResourceLocation> startJigsawName, int size, int yOffset,
                              int maxDistanceFromCenter, boolean useExpansionHack, boolean islandCenter,
                              int minIslandRadius, float islandChance) {
        super(settings);
        this.layer = layer;
        this.startPool = startPool;
        this.startJigsawName = startJigsawName;
        this.size = size;
        this.yOffset = yOffset;
        this.maxDistanceFromCenter = maxDistanceFromCenter;
        this.useExpansionHack = useExpansionHack;
        this.islandCenter = islandCenter;
        this.minIslandRadius = minIslandRadius;
        this.islandChance = islandChance;
        this.chanceSalt = IslandMath.salt(layer + "|" + startPool.unwrapKey().map(k -> k.location().toString()).orElse("inline"));
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
        if (islandCenter) {
            SkyWorld.Island island = islandCenteredIn(sky, skyLayer, chunk);
            if (island == null) {
                return Optional.empty();
            }
            x = island.plan().centerX();
            z = island.plan().centerZ();
        }
        Integer surface = sky.surfaceAt(skyLayer, x, z);
        if (surface == null) {
            return Optional.empty();
        }
        BlockPos start = new BlockPos(x, surface + yOffset, z);
        Optional<GenerationStub> stub = JigsawPlacement.addPieces(context, startPool, startJigsawName, size, start,
                useExpansionHack, Optional.empty(), maxDistanceFromCenter, PoolAliasLookup.EMPTY, DimensionPadding.ZERO,
                LiquidSettings.APPLY_WATERLOGGING);
        // Pieces are laid out later, when the stub runs: do that with the island context active.
        SkyStructureContext.Active active = new SkyStructureContext.Active(sky, skyLayer, surface + 1);
        return stub.map(s -> new GenerationStub(s.position(), builder -> SkyStructureContext.run(active, () -> {
            StructurePiecesBuilder laidOut = new StructurePiecesBuilder();
            s.generator().map(
                    consumer -> {
                        consumer.accept(laidOut);
                        return null;
                    },
                    built -> {
                        built.build().pieces().forEach(laidOut::addPiece);
                        return null;
                    });
            List<StructurePiece> pieces = laidOut.build().pieces();
            for (int i = 0; i < pieces.size(); i++) {
                // The first piece is the start, which is always on the island.
                if (i == 0 || overIsland(sky, skyLayer, pieces.get(i).getBoundingBox())) {
                    builder.addPiece(pieces.get(i));
                }
            }
        })));
    }

    /**
     * True if a piece's footprint is over the island: its centre and at least 3 of its 4 corners
     * (1 block in) have island under them, near the piece's height. Pieces off the edge would
     * otherwise float in the air next to the island.
     */
    private static boolean overIsland(SkyWorld sky, SkyWorld.CompiledLayer layer, BoundingBox box) {
        int x0 = Math.min(box.minX() + 1, box.maxX());
        int x1 = Math.max(box.maxX() - 1, box.minX());
        int z0 = Math.min(box.minZ() + 1, box.maxZ());
        int z1 = Math.max(box.maxZ() - 1, box.minZ());
        if (!islandNear(sky, layer, (box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2, box.minY())) {
            return false;
        }
        int corners = (islandNear(sky, layer, x0, z0, box.minY()) ? 1 : 0) + (islandNear(sky, layer, x1, z0, box.minY()) ? 1 : 0)
                + (islandNear(sky, layer, x0, z1, box.minY()) ? 1 : 0) + (islandNear(sky, layer, x1, z1, box.minY()) ? 1 : 0);
        return corners >= 3;
    }

    private static boolean islandNear(SkyWorld sky, SkyWorld.CompiledLayer layer, int x, int z, int y) {
        Integer top = sky.surfaceAt(layer, x, z);
        return top != null && Math.abs(top - y) <= 16;
    }

    /**
     * The island of this layer whose centre is in this chunk, if it's big enough and passes its
     * {@code island_chance} roll. One fixed roll per island, so an island gets the structure once or
     * never, whatever the structure set's spacing.
     */
    @org.jetbrains.annotations.Nullable
    private SkyWorld.Island islandCenteredIn(SkyWorld sky, SkyWorld.CompiledLayer skyLayer, ChunkPos chunk) {
        SkyWorld.Island[] found = {null};
        sky.forEachIslandNear(skyLayer, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ(), 0, island -> {
            int cx = island.plan().centerX();
            int cz = island.plan().centerZ();
            if (cx >= chunk.getMinBlockX() && cx <= chunk.getMaxBlockX() && cz >= chunk.getMinBlockZ() && cz <= chunk.getMaxBlockZ()
                    && island.plan().radius() >= minIslandRadius
                    && IslandMath.unit(IslandMath.mix(island.plan().seed() ^ chanceSalt)) < islandChance) {
                found[0] = island;
            }
        });
        return found[0];
    }

    @Override
    public StructureType<?> type() {
        return LostInTheSky.SKY_JIGSAW.get();
    }
}
