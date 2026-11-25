package com.reetro.lostinthesky.network;

import com.reetro.lostinthesky.LostInTheSky;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server to client: the player wrench-clicked a ship brain. Toggles link mode for that brain on the client and
 * lists the blocks on its ship that can be linked (to highlight them).
 */
public record BrainLinkModePayload(BlockPos brain, List<BlockPos> candidates) implements CustomPacketPayload {
    public static final Type<BrainLinkModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LostInTheSky.MODID, "brain_link_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BrainLinkModePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BrainLinkModePayload::brain,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), BrainLinkModePayload::candidates,
            BrainLinkModePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
