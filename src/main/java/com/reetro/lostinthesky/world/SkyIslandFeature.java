package com.reetro.lostinthesky.world;

import com.mojang.serialization.Codec;
import com.reetro.lostinthesky.LostInTheSky;
import com.reetro.lostinthesky.data.SkyBiome;
import com.reetro.lostinthesky.data.SkyEncounter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.util.RandomSource;

import java.util.Optional;

/**
 * Builds the sky islands for one chunk.
 *
 * <p>It runs as a normal feature in the last decoration step of every overworld biome (added by
 * {@code data/lost_in_the_sky/neoforge/biome_modifier/add_sky_islands.json}). It only writes the 16x16
 * columns of its own chunk, so it never fights with the terrain generator, and runs last so ground
 * trees and structures are already placed. That is what makes it work with any worldgen mod.
 */
public class SkyIslandFeature extends Feature<NoneFeatureConfiguration> {
    /** Templates bigger than this could reach outside the area a feature may write to. */
    private static final int MAX_TEMPLATE_SIZE = 32;

    public SkyIslandFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) {
            return false;
        }
        SkyWorld sky = SkyWorld.get();
        if (sky == null) {
            return false;
        }

        ChunkPos chunk = new ChunkPos(context.origin());
        int minX = chunk.getMinBlockX();
        int minZ = chunk.getMinBlockZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        RandomSource random = context.random();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] column = new int[2];
        boolean placedAny = false;

        for (SkyWorld.CompiledLayer layer : sky.layers()) {
            // Surface of this layer's islands in each column of the chunk, for decorations.
            int[] tops = new int[256];
            SkyWorld.Island[] owners = new SkyWorld.Island[256];

            int centerX = minX + 8;
            int centerZ = minZ + 8;
            boolean[] layerPlaced = {false};
            sky.forEachIslandNear(layer, centerX, centerZ, 1, island -> {
                SkyWorld.CompiledBiome biome = island.biome();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = minX + lx;
                        int z = minZ + lz;
                        if (!IslandMath.column(island.plan(), island.centerY(), x, z, column)) {
                            continue;
                        }
                        int top = Math.min(column[0], maxY);
                        int bottom = Math.max(column[1], minY);
                        fillColumn(level, pos, x, z, bottom, top, biome);
                        int idx = lx * 16 + lz;
                        if (owners[idx] == null || top > tops[idx]) {
                            tops[idx] = top;
                            owners[idx] = island;
                        }
                        layerPlaced[0] = true;
                    }
                }
            });
            if (!layerPlaced[0]) {
                continue;
            }
            placedAny = true;
            decorate(context, level, random, minX, minZ, tops, owners);
            placeEncounters(sky, layer, level, random, chunk, pos);
        }
        return placedAny;
    }

    private static void fillColumn(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                   int bottom, int top, SkyWorld.CompiledBiome biome) {
        SkyBiome def = biome == null ? null : biome.def();
        BlockState topState = def == null ? net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState() : def.top();
        BlockState fillerState = def == null ? net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState() : def.filler();
        BlockState coreState = def == null ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : def.core();
        int fillerDepth = def == null ? 3 : def.fillerDepth();
        for (int y = bottom; y <= top; y++) {
            pos.set(x, y, z);
            // Only fill air: never overwrite mountains, trees or structures already there.
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            BlockState state = y == top ? topState : (y >= top - fillerDepth ? fillerState : coreState);
            level.setBlock(pos, state, 2);
        }
    }

    private static void decorate(FeaturePlaceContext<NoneFeatureConfiguration> context, WorldGenLevel level,
                                 RandomSource random, int minX, int minZ, int[] tops, SkyWorld.Island[] owners) {
        // Each biome present in this chunk gets its decorations tried on its own columns.
        java.util.Set<SkyWorld.CompiledBiome> present = new java.util.LinkedHashSet<>();
        for (SkyWorld.Island owner : owners) {
            if (owner != null && owner.biome() != null) {
                present.add(owner.biome());
            }
        }
        for (SkyWorld.CompiledBiome biome : present) {
            for (SkyWorld.CompiledDecoration decoration : biome.decorations()) {
                for (int t = 0; t < decoration.tries(); t++) {
                    int idx = random.nextInt(256);
                    if (random.nextFloat() >= decoration.chance()) {
                        continue;
                    }
                    SkyWorld.Island owner = owners[idx];
                    if (owner == null || owner.biome() != biome) {
                        continue;
                    }
                    BlockPos at = new BlockPos(minX + idx / 16, tops[idx] + 1, minZ + idx % 16);
                    try {
                        decoration.feature().value().place(level, context.chunkGenerator(), random, at);
                    } catch (RuntimeException e) {
                        LostInTheSky.LOGGER.debug("Sky decoration failed at {}", at, e);
                    }
                }
            }
        }
    }

    /** Encounters are placed once, by the chunk that holds the island's centre. */
    private static void placeEncounters(SkyWorld sky, SkyWorld.CompiledLayer layer, WorldGenLevel level,
                                        RandomSource random, ChunkPos chunk, BlockPos.MutableBlockPos pos) {
        sky.forEachIslandNear(layer, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ(), 0, island -> {
            SkyWorld.CompiledEncounter encounter = island.encounter();
            if (encounter == null) {
                return;
            }
            int cx = island.plan().centerX();
            int cz = island.plan().centerZ();
            if (cx < chunk.getMinBlockX() || cx > chunk.getMaxBlockX() || cz < chunk.getMinBlockZ() || cz > chunk.getMaxBlockZ()) {
                return;
            }
            Integer surface = sky.surfaceAt(layer, cx, cz);
            if (surface == null) {
                return;
            }
            BlockPos origin = new BlockPos(cx, surface + encounter.def().yOffset(), cz);
            placeEncounter(level, random, encounter, origin);
        });
    }

    private static void placeEncounter(WorldGenLevel level, RandomSource random,
                                       SkyWorld.CompiledEncounter encounter, BlockPos origin) {
        SkyEncounter def = encounter.def();
        if (def.template().isPresent()) {
            Optional<StructureTemplate> template = level.getLevel().getStructureManager().get(def.template().get());
            if (template.isEmpty()) {
                LostInTheSky.LOGGER.warn("Sky encounter {}: template {} not found", encounter.id(), def.template().get());
            } else {
                Vec3i size = template.get().getSize();
                if (size.getX() > MAX_TEMPLATE_SIZE || size.getZ() > MAX_TEMPLATE_SIZE) {
                    LostInTheSky.LOGGER.warn("Sky encounter {}: template is {}x{}, max is {}x{}. Use a sky_jigsaw structure for big builds.",
                            encounter.id(), size.getX(), size.getZ(), MAX_TEMPLATE_SIZE, MAX_TEMPLATE_SIZE);
                } else {
                    BlockPos corner = origin.offset(-size.getX() / 2, 0, -size.getZ() / 2);
                    template.get().placeInWorld(level, corner, corner, new StructurePlaceSettings(), random, 2);
                }
            }
        }

        for (SkyEncounter.EntityEntry entry : def.entities()) {
            Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entry.type());
            if (type.isEmpty()) {
                LostInTheSky.LOGGER.debug("Sky encounter {}: entity {} not installed, skipped", encounter.id(), entry.type());
                continue;
            }
            for (int n = 0; n < entry.count(); n++) {
                Entity entity = type.get().create(level.getLevel());
                if (entity == null) {
                    continue;
                }
                BlockPos at = origin.offset(entry.offset());
                double spread = entry.count() > 1 ? 1.5 : 0.0;
                entity.moveTo(at.getX() + 0.5 + (random.nextDouble() - 0.5) * spread, at.getY(),
                        at.getZ() + 0.5 + (random.nextDouble() - 0.5) * spread, random.nextFloat() * 360f, 0f);
                if (entity instanceof Mob mob) {
                    mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null);
                    if (entry.persistent()) {
                        mob.setPersistenceRequired();
                    }
                }
                level.addFreshEntityWithPassengers(entity);
            }
        }
    }
}
