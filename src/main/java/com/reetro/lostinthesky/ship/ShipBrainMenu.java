package com.reetro.lostinthesky.ship;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The ship brain's order screen. No slots: the buttons go through vanilla's menu button click
 * ({@link #clickMenuButton}) and the state comes back through {@link ContainerData}.
 * It only stays open while the brain is still on a physics object and the player is in reach.
 */
public class ShipBrainMenu extends AbstractContainerMenu {
    public static final int DATA_COUNT = 5;
    private static final double MAX_REACH_SQR = 8.0 * 8.0;

    @Nullable
    private final ShipBrainBlockEntity brain; // server side only
    private final BlockPos pos;
    private final ContainerData data;

    /** Client side, from the open-menu packet. */
    public ShipBrainMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ShipBrains.SHIP_BRAIN_MENU.get(), containerId);
        this.brain = null;
        this.pos = buf.readBlockPos();
        this.data = new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
    }

    /** Server side. */
    public ShipBrainMenu(int containerId, Inventory inventory, ShipBrainBlockEntity brain, ContainerData data) {
        super(ShipBrains.SHIP_BRAIN_MENU.get(), containerId);
        this.brain = brain;
        this.pos = brain.getBlockPos();
        this.data = data;
        addDataSlots(data);
    }

    public ShipBrainBlockEntity.Mode mode() {
        return ShipBrainBlockEntity.Mode.byId(data.get(0));
    }

    public int followDistance() {
        return data.get(1);
    }

    public boolean onShip() {
        return data.get(2) != 0;
    }

    public int linkCount() {
        return data.get(3);
    }

    public boolean locked() {
        return data.get(4) != 0;
    }

    public BlockPos pos() {
        return pos;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (brain == null || !(player instanceof ServerPlayer serverPlayer) || !brain.isOnShip()) {
            return false;
        }
        brain.handleButton(serverPlayer, id);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (brain == null) {
            return true;
        }
        if (brain.isRemoved() || brain.getLevel() != player.level() || !brain.isOnShip()) {
            return false;
        }
        // The block lives in Sable's plot far away; measure to where the ship actually is.
        return Sable.HELPER.distanceSquaredWithSubLevels(player.level(), player.position(), Vec3.atCenterOf(pos)) <= MAX_REACH_SQR;
    }
}
