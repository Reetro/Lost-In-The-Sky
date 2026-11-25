package com.reetro.lostinthesky.network;

import com.reetro.lostinthesky.LostInTheSky;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client to server: in link mode, the player wrench-clicked {@code target}; link or unlink it to {@code brain}. */
public record BrainToggleLinkPayload(BlockPos brain, BlockPos target) implements CustomPacketPayload {
    public static final Type<BrainToggleLinkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LostInTheSky.MODID, "brain_toggle_link"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BrainToggleLinkPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BrainToggleLinkPayload::brain,
            BlockPos.STREAM_CODEC, BrainToggleLinkPayload::target,
            BrainToggleLinkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
