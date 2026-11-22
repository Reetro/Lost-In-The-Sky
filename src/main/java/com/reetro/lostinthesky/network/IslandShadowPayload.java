package com.reetro.lostinthesky.network;

import com.reetro.lostinthesky.LostInTheSky;
import com.reetro.lostinthesky.world.IslandShadows;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/** Server to client: the island underside Y of each column in one chunk (see {@link IslandShadows}). */
public record IslandShadowPayload(ChunkPos pos, int[] bottoms) implements CustomPacketPayload {
    public static final Type<IslandShadowPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LostInTheSky.MODID, "island_shadows"));

    /** Y values are sent as varints offset by this; 0 means no island. Vanilla min_y is never below -2032. */
    private static final int Y_OFFSET = 4096;

    public static final StreamCodec<FriendlyByteBuf, IslandShadowPayload> STREAM_CODEC =
            StreamCodec.of(IslandShadowPayload::write, IslandShadowPayload::read);

    private static void write(FriendlyByteBuf buf, IslandShadowPayload payload) {
        buf.writeLong(payload.pos.toLong());
        for (int bottom : payload.bottoms) {
            buf.writeVarInt(bottom == IslandShadows.NO_ISLAND ? 0 : bottom + Y_OFFSET);
        }
    }

    private static IslandShadowPayload read(FriendlyByteBuf buf) {
        ChunkPos pos = new ChunkPos(buf.readLong());
        int[] bottoms = new int[256];
        for (int i = 0; i < 256; i++) {
            int v = buf.readVarInt();
            bottoms[i] = v == 0 ? IslandShadows.NO_ISLAND : v - Y_OFFSET;
        }
        return new IslandShadowPayload(pos, bottoms);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
