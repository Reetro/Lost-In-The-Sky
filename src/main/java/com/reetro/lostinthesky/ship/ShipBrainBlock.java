package com.reetro.lostinthesky.ship;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.reetro.lostinthesky.network.BrainLinkModePayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The ship brain: put it on a Sable physics object (an assembled airship) and right-click it to give the
 * ship orders. The work is done by {@link ShipBrainBlockEntity}; off a ship the block does nothing.
 */
public class ShipBrainBlock extends Block implements EntityBlock {
    public static final MapCodec<ShipBrainBlock> CODEC = simpleCodec(ShipBrainBlock::new);

    public ShipBrainBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShipBrainBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ShipBrains.SHIP_BRAIN_BE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<ShipBrainBlockEntity>) ShipBrainBlockEntity::serverTick;
    }

    /** Wrench: start or stop linking thrusters (the client highlights what can be linked). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!ShipBrains.isWrench(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof ShipBrainBlockEntity brain && player instanceof ServerPlayer serverPlayer) {
            if (!brain.isOnShip()) {
                player.displayClientMessage(Component.translatable("message.lost_in_the_sky.ship_brain.not_on_ship"), true);
            } else {
                PacketDistributor.sendToPlayer(serverPlayer, new BrainLinkModePayload(pos.immutable(), brain.linkCandidates()));
            }
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ShipBrainBlockEntity brain) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!brain.isOnShip()) {
            player.displayClientMessage(Component.translatable("message.lost_in_the_sky.ship_brain.not_on_ship"), true);
            return InteractionResult.CONSUME;
        }
        serverPlayer.openMenu(brain, buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }
}
