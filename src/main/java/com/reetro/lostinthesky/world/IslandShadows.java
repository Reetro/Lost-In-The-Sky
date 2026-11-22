package com.reetro.lostinthesky.world;

import com.reetro.lostinthesky.client.IslandShadowsClient;
import com.reetro.lostinthesky.network.IslandShadowPayload;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Supplies the per-column island undersides that {@link com.reetro.lostinthesky.mixin.ChunkSkyLightSourcesMixin}
 * uses to let sky light pass through islands.
 *
 * <p>The server works them out from {@link SkyWorld}. The client can't (it has no seed or chunk
 * generator), so the server sends them in an {@link IslandShadowPayload} right after each chunk
 * that has an island over it. The client keeps them in {@link #CLIENT_BOTTOMS} and rebuilds that
 * chunk's light sources.
 */
public final class IslandShadows {
    public static final int NO_ISLAND = Integer.MAX_VALUE;

    /** Client side: undersides received from the server, by {@link ChunkPos#toLong()}. */
    private static final Map<Long, int[]> CLIENT_BOTTOMS = new ConcurrentHashMap<>();

    private IslandShadows() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(IslandShadows::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(IslandShadows::onChunkSent);
        NeoForge.EVENT_BUS.addListener(IslandShadows::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(IslandShadows::onLevelUnload);
    }

    /** Island undersides for a chunk's 256 columns, or null if no island is over it. */
    @Nullable
    public static int[] bottomsFor(ChunkAccess chunk) {
        if (chunk.getHeight() < SkyWorld.MIN_SKY_WORLD_HEIGHT) {
            return null;
        }
        if (chunk instanceof LevelChunk levelChunk && levelChunk.getLevel().isClientSide()) {
            return CLIENT_BOTTOMS.get(chunk.getPos().toLong());
        }
        SkyWorld sky = SkyWorld.get();
        return sky == null ? null : computeBottoms(sky, chunk.getPos());
    }

    @Nullable
    private static int[] computeBottoms(SkyWorld sky, ChunkPos pos) {
        int[] bottoms = null;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                Integer bottom = sky.lowestIslandBottomAt(pos.getMinBlockX() + x, pos.getMinBlockZ() + z);
                if (bottom != null) {
                    if (bottoms == null) {
                        bottoms = new int[256];
                        Arrays.fill(bottoms, NO_ISLAND);
                    }
                    bottoms[x + z * 16] = bottom;
                }
            }
        }
        return bottoms;
    }

    // ------------------------------------------------------------------ networking

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(IslandShadowPayload.TYPE, IslandShadowPayload.STREAM_CODEC,
                // Only ever runs on the client; the class reference is resolved lazily.
                (payload, context) -> IslandShadowsClient.handle(payload));
    }

    private static void onChunkSent(ChunkWatchEvent.Sent event) {
        int[] bottoms = ((IslandShadowSources) event.getChunk().getSkyLightSources()).lostinthesky$getIslandBottoms();
        if (bottoms != null) {
            PacketDistributor.sendToPlayer(event.getPlayer(), new IslandShadowPayload(event.getPos(), bottoms));
        }
    }

    /** Client side: remember undersides for a chunk. The caller rebuilds the chunk's light sources. */
    public static void putClientBottoms(ChunkPos pos, int[] bottoms) {
        CLIENT_BOTTOMS.put(pos.toLong(), bottoms);
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            CLIENT_BOTTOMS.remove(event.getChunk().getPos().toLong());
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            CLIENT_BOTTOMS.clear();
        }
    }
}
