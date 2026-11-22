package com.reetro.lostinthesky.client;

import com.reetro.lostinthesky.network.IslandShadowPayload;
import com.reetro.lostinthesky.world.IslandShadows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Client-only half of {@link IslandShadows}. Runs on the client main thread. */
public final class IslandShadowsClient {
    private IslandShadowsClient() {
    }

    public static void handle(IslandShadowPayload payload) {
        IslandShadows.putClientBottoms(payload.pos(), payload.bottoms());
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // The payload follows the chunk packet, so the chunk is normally loaded already. Its light
        // values came from the server and are correct; only the sources used for later relighting
        // need rebuilding (fillFrom picks the stored undersides up).
        if (level.getChunkSource().getChunk(payload.pos().x, payload.pos().z, ChunkStatus.FULL, false) instanceof LevelChunk chunk) {
            chunk.initializeLightSources();
        }
    }
}
