package com.reetro.lostinthesky.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.reetro.lostinthesky.LostInTheSky;
import com.reetro.lostinthesky.network.BrainLinkModePayload;
import com.reetro.lostinthesky.network.BrainToggleLinkPayload;
import com.reetro.lostinthesky.ship.ShipBrainBlockEntity;
import com.reetro.lostinthesky.ship.ShipBrains;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side of linking thrusters to a ship brain.
 *
 * <ul>
 *   <li>Holding a wrench: a blue line from every nearby brain to each block it's linked to.</li>
 *   <li>Wrench-click a brain: link mode (the server sends {@link BrainLinkModePayload}). The blocks on that ship
 *   that can move it are outlined (blue = linked, yellow = not). Wrench-click one to link/unlink it
 *   ({@link BrainToggleLinkPayload}; the click is cancelled so the wrench doesn't rotate it). Wrench-click the
 *   brain again or put the wrench away to stop.</li>
 * </ul>
 *
 * Ship blocks live in Sable's plot, so every position is moved to where the ship really is with the ship's
 * render pose before drawing.
 */
@EventBusSubscriber(modid = LostInTheSky.MODID, value = Dist.CLIENT)
public final class ShipBrainLinkClient {
    private static final int LINE_COLOR = 0xFF3D8BFF;
    private static final int LINKED_COLOR = 0xFF3D8BFF;
    private static final int CANDIDATE_COLOR = 0xFFFFD23D;
    private static final int BRAIN_COLOR = 0xFFFFFFFF;
    private static final double DRAW_DISTANCE_SQR = 160.0 * 160.0;

    @Nullable
    private static BlockPos activeBrain;
    private static List<BlockPos> candidates = List.of();

    private ShipBrainLinkClient() {
    }

    public static void handle(BrainLinkModePayload payload) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (payload.brain().equals(activeBrain)) {
            stop();
            return;
        }
        activeBrain = payload.brain();
        candidates = List.copyOf(payload.candidates());
        if (player != null) {
            player.displayClientMessage(Component.translatable(candidates.isEmpty()
                    ? "message.lost_in_the_sky.ship_brain.link_none"
                    : "message.lost_in_the_sky.ship_brain.link_start", candidates.size()), true);
        }
    }

    private static void stop() {
        if (activeBrain != null) {
            activeBrain = null;
            candidates = List.of();
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.lost_in_the_sky.ship_brain.link_stop"), true);
            }
        }
    }

    @Nullable
    private static ShipBrainBlockEntity activeBrainEntity() {
        Minecraft mc = Minecraft.getInstance();
        if (activeBrain == null || mc.level == null) {
            return null;
        }
        return mc.level.getBlockEntity(activeBrain) instanceof ShipBrainBlockEntity brain ? brain : null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (activeBrain != null && (player == null || !ShipBrains.isWrench(player.getMainHandItem()) || activeBrainEntity() == null)) {
            stop();
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide() || activeBrain == null || event.getHand() != InteractionHand.MAIN_HAND
                || !ShipBrains.isWrench(event.getItemStack())) {
            return;
        }
        BlockPos pos = event.getPos();
        ShipBrainBlockEntity brain = activeBrainEntity();
        if (pos.equals(activeBrain) || brain == null || !candidates.contains(pos) && !brain.links().contains(pos)) {
            return;
        }
        PacketDistributor.sendToServer(new BrainToggleLinkPayload(activeBrain, pos.immutable()));
        event.getEntity().swing(InteractionHand.MAIN_HAND);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null
                || !ShipBrains.isWrench(player.getMainHandItem()) && !ShipBrains.isWrench(player.getOffhandItem())) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(ShipBrainRenderTypes.LINES_SEE_THROUGH);

        for (ShipBrainBlockEntity brain : new ArrayList<>(ShipBrainBlockEntity.CLIENT_BRAINS)) {
            if (brain.isRemoved() || brain.getLevel() != mc.level || brain.links().isEmpty()) {
                continue;
            }
            Vector3d from = toWorld(brain.getBlockPos(), 0.5, 0.5, 0.5, partialTick);
            if (from.distanceSquared(camera.x, camera.y, camera.z) > DRAW_DISTANCE_SQR) {
                continue;
            }
            for (BlockPos link : brain.links()) {
                line(lines, pose, camera, from, toWorld(link, 0.5, 0.5, 0.5, partialTick), LINE_COLOR);
            }
        }

        ShipBrainBlockEntity active = activeBrainEntity();
        if (active != null) {
            box(lines, pose, camera, active.getBlockPos(), partialTick, BRAIN_COLOR);
            for (BlockPos candidate : candidates) {
                box(lines, pose, camera, candidate, partialTick, active.links().contains(candidate) ? LINKED_COLOR : CANDIDATE_COLOR);
            }
            for (BlockPos link : active.links()) {
                if (!candidates.contains(link)) {
                    box(lines, pose, camera, link, partialTick, LINKED_COLOR);
                }
            }
        }
        buffers.endBatch(ShipBrainRenderTypes.LINES_SEE_THROUGH);
    }

    /** A point inside block {@code pos} (offsets 0..1), moved out of Sable's plot to where the ship is drawn. */
    private static Vector3d toWorld(BlockPos pos, double dx, double dy, double dz, float partialTick) {
        Vector3d point = new Vector3d(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(pos);
        return subLevel == null ? point : subLevel.renderPose(partialTick).transformPosition(point);
    }

    private static void box(VertexConsumer lines, PoseStack.Pose pose, Vec3 camera, BlockPos pos, float partialTick, int color) {
        double lo = -0.02;
        double hi = 1.02;
        Vector3d[] corners = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = toWorld(pos, (i & 1) == 0 ? lo : hi, (i & 2) == 0 ? lo : hi, (i & 4) == 0 ? lo : hi, partialTick);
        }
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit < 8; bit <<= 1) {
                if ((i & bit) == 0) {
                    line(lines, pose, camera, corners[i], corners[i | bit], color);
                }
            }
        }
    }

    private static void line(VertexConsumer lines, PoseStack.Pose pose, Vec3 camera, Vector3d from, Vector3d to, int color) {
        float x0 = (float) (from.x - camera.x);
        float y0 = (float) (from.y - camera.y);
        float z0 = (float) (from.z - camera.z);
        float x1 = (float) (to.x - camera.x);
        float y1 = (float) (to.y - camera.y);
        float z1 = (float) (to.z - camera.z);
        float nx = x1 - x0;
        float ny = y1 - y0;
        float nz = z1 - z0;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-4F) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        lines.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz);
    }
}
