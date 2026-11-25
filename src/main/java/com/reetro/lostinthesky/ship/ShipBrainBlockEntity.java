package com.reetro.lostinthesky.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The ship brain flies its ship with the thrusters it's linked to (propellers, propeller bearings, fans: anything
 * {@link ThrustControl} sees pushing the ship). It adds no force of its own: it only sets each linked thruster's
 * throttle, so the ship can do no more than its thrusters allow, and falls if they can't hold it up.
 *
 * <p>Each game tick ({@link #serverTick}) it works out a goal (follow a player / stay put), finds its linked
 * thrusters and checks the Physics Staff lock. Each physics step ({@link #sable$physicsTick}) it decides what
 * acceleration and turning the ship needs (reach the goal, beat gravity, stay level, face the way it's going if
 * its thrusters mostly push one way) and asks {@link ThrustAllocator} for the throttles that come closest, using
 * each thruster's measured full-power force and where it sits relative to the centre of mass. So it works out on
 * its own how to turn with whatever layout it was given (differential thrust, side thrusters, ...).
 *
 * <p>Off: the brain lets go, thrusters run as if it weren't there. Locked with the Physics Staff, or off a ship:
 * linked thrusters idle.
 */
public class ShipBrainBlockEntity extends BlockEntity implements BlockEntitySubLevelActor, MenuProvider {
    public enum Mode {
        OFF, FOLLOW, STAY;

        public static Mode byId(int id) {
            return id >= 0 && id < values().length ? values()[id] : OFF;
        }
    }

    public static final int MIN_DISTANCE = 4;
    public static final int MAX_DISTANCE = 64;
    public static final int DISTANCE_STEP = 4;
    public static final int DEFAULT_DISTANCE = 16;
    public static final int MAX_LINKS = 64;

    // Menu button ids (see ShipBrainMenu#clickMenuButton).
    public static final int BUTTON_OFF = 0;
    public static final int BUTTON_FOLLOW = 1;
    public static final int BUTTON_STAY = 2;
    public static final int BUTTON_DISTANCE_DOWN = 3;
    public static final int BUTTON_DISTANCE_UP = 4;

    /** Brains loaded on the client, for drawing link lines. */
    public static final Set<ShipBrainBlockEntity> CLIENT_BRAINS = Collections.newSetFromMap(new WeakHashMap<>());

    // Flight controller tuning.
    /** Blocks per second. */
    private static final double MAX_SPEED = 12.0;
    /** Desired speed per block of distance to the goal (1/s). */
    private static final double POSITION_GAIN = 0.8;
    /** Closer than this to the goal counts as there. */
    private static final double DEAD_ZONE = 0.5;
    /** Acceleration asked for per block/s of velocity error (1/s). */
    private static final double VELOCITY_GAIN = 1.5;
    /** Most acceleration asked for, blocks/s^2 (on top of holding against gravity). */
    private static final double MAX_ACCEL = 8.0;
    /** Turn rate (rad/s) per radian of tilt when levelling. */
    private static final double LEVEL_GAIN = 2.0;
    /** Turn rate (rad/s) per radian of heading error. */
    private static final double YAW_GAIN = 1.0;
    private static final double MAX_YAW_RATE = 0.6;
    /** Angular acceleration asked for per rad/s of spin error (1/s). */
    private static final double ANGULAR_GAIN = 3.0;
    private static final double MAX_ANGULAR_ACCEL = 2.0;
    /** Only turn to face the goal when it's farther than this horizontally. */
    private static final double FACE_DISTANCE = 4.0;
    /** Thrusters push "mostly one way" (so the ship should face its goal) above this share of their sideways force. */
    private static final double FORWARD_BIAS = 0.3;
    /** Holding altitude matters more than the rest. */
    private static final double VERTICAL_WEIGHT = 2.0;

    private Mode mode = Mode.OFF;
    private int followDistance = DEFAULT_DISTANCE;
    @Nullable
    private UUID followPlayer;
    @Nullable
    private Vector3d stayPos;
    /** Linked thruster positions (plot coordinates, so they move with the ship). Replaced, never mutated. */
    private volatile List<BlockPos> links = List.of();

    // Set each game tick for the physics step.
    @Nullable
    private volatile Vector3dc goal;
    private volatile Object[] thrusters = new Object[0];
    private volatile boolean locked;
    private volatile boolean released = true;
    private double[] throttles = new double[0];

    /** Synced to an open menu. */
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> mode.ordinal();
                case 1 -> followDistance;
                case 2 -> isOnShip() ? 1 : 0;
                case 3 -> links.size();
                case 4 -> locked ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return ShipBrainMenu.DATA_COUNT;
        }
    };

    public ShipBrainBlockEntity(BlockPos pos, BlockState state) {
        super(ShipBrains.SHIP_BRAIN_BE.get(), pos, state);
    }

    /** True if this block sits on a Sable physics object (an assembled ship). */
    public boolean isOnShip() {
        return getSubLevel() != null;
    }

    @Nullable
    private SubLevel getSubLevel() {
        return level == null ? null : Sable.HELPER.getContaining(this);
    }

    public List<BlockPos> links() {
        return links;
    }

    /** The brain block's centre in world space (moved with the ship). */
    private Vector3d worldCenter(SubLevel subLevel, Vector3d out) {
        out.set(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5);
        return subLevel.logicalPose().transformPosition(out);
    }

    // ---------------------------------------------------------------- linking

    /** Blocks on this ship that can move it: the ones the wrench can link. */
    public List<BlockPos> linkCandidates() {
        SubLevel subLevel = getSubLevel();
        List<BlockPos> candidates = new ArrayList<>();
        if (subLevel == null) {
            return candidates;
        }
        for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
            if (actor != this && actor instanceof BlockEntity be && !be.isRemoved() && ThrustControl.canMoveShip(actor)) {
                candidates.add(be.getBlockPos().immutable());
            }
        }
        return candidates;
    }

    public boolean isLinkCandidate(BlockPos pos) {
        return linkCandidates().contains(pos);
    }

    /** Links or unlinks a thruster. Returns true if it's linked now; false if unlinked or the brain is full. */
    public boolean toggleLink(BlockPos pos) {
        List<BlockPos> next = new ArrayList<>(links);
        boolean linked;
        if (next.remove(pos)) {
            linked = false;
            if (level != null && level.getBlockEntity(pos) instanceof BlockEntity be) {
                ThrustControl.clearThrottle(be);
            }
        } else if (next.size() < MAX_LINKS) {
            next.add(pos.immutable());
            linked = true;
        } else {
            return false;
        }
        links = List.copyOf(next);
        syncToClient();
        return linked;
    }

    private void syncToClient() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ---------------------------------------------------------------- orders

    void handleButton(ServerPlayer player, int id) {
        switch (id) {
            case BUTTON_OFF -> {
                mode = Mode.OFF;
                followPlayer = null;
                stayPos = null;
            }
            case BUTTON_FOLLOW -> {
                mode = Mode.FOLLOW;
                followPlayer = player.getUUID();
                stayPos = null;
            }
            case BUTTON_STAY -> {
                SubLevel subLevel = getSubLevel();
                if (subLevel == null) {
                    return;
                }
                mode = Mode.STAY;
                followPlayer = null;
                stayPos = worldCenter(subLevel, new Vector3d());
            }
            case BUTTON_DISTANCE_DOWN -> followDistance = Math.max(MIN_DISTANCE, followDistance - DISTANCE_STEP);
            case BUTTON_DISTANCE_UP -> followDistance = Math.min(MAX_DISTANCE, followDistance + DISTANCE_STEP);
            default -> {
                return;
            }
        }
        goal = null;
        setChanged();
    }

    // ---------------------------------------------------------------- game tick

    public static void serverTick(Level level, BlockPos pos, BlockState state, ShipBrainBlockEntity brain) {
        brain.updateServer((ServerLevel) level);
    }

    private void updateServer(ServerLevel level) {
        SubLevel subLevel = getSubLevel();
        List<BlockPos> currentLinks = links;
        Object[] found = new Object[currentLinks.size()];
        for (int i = 0; i < found.length; i++) {
            BlockEntity be = level.getBlockEntity(currentLinks.get(i));
            found[i] = be instanceof BlockEntitySubLevelActor && be != this ? be : null;
        }
        thrusters = found;
        locked = subLevel != null && PhysicsLock.isLocked(level, subLevel);

        if (mode == Mode.OFF) {
            goal = null;
            releaseThrottles(found);
            return;
        }
        released = false;
        if (subLevel == null) {
            goal = null;
            idleThrottles(found);
            return;
        }
        goal = switch (mode) {
            case OFF -> null;
            case STAY -> stayPos;
            case FOLLOW -> followGoal(level, subLevel);
        };
    }

    /**
     * A point {@link #followDistance} blocks from the player horizontally, on the ship's side of them, and
     * half that above their feet (at least 4). Null (hover) if the player is gone or riding this ship.
     */
    @Nullable
    private Vector3dc followGoal(ServerLevel level, SubLevel subLevel) {
        if (followPlayer == null) {
            return null;
        }
        Player player = level.getPlayerByUUID(followPlayer);
        if (player == null || player.isSpectator() || Sable.HELPER.getContaining(player) == subLevel) {
            return null;
        }
        Vector3d ship = worldCenter(subLevel, new Vector3d());
        double dx = ship.x - player.getX();
        double dz = ship.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-3) {
            dx = 1.0;
            dz = 0.0;
            horizontal = 1.0;
        }
        double scale = followDistance / horizontal;
        double height = Math.max(4.0, followDistance * 0.5);
        return new Vector3d(player.getX() + dx * scale, player.getY() + height, player.getZ() + dz * scale);
    }

    private void releaseThrottles(Object[] actors) {
        if (!released) {
            for (Object actor : actors) {
                if (actor != null) {
                    ThrustControl.clearThrottle(actor);
                }
            }
            released = true;
        }
    }

    private static void idleThrottles(Object[] actors) {
        for (Object actor : actors) {
            if (actor != null) {
                ThrustControl.setThrottle(actor, 0.0);
            }
        }
    }

    // ---------------------------------------------------------------- physics step

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double dt) {
        Object[] actors = thrusters;
        if (mode == Mode.OFF || actors.length == 0) {
            return;
        }
        if (locked || handle == null || !handle.isValid() || dt <= 0.0) {
            idleThrottles(actors);
            return;
        }
        MassData mass = subLevel.getMassTracker();
        if (mass == null || mass.isInvalid() || mass.getMass() <= 0.0) {
            idleThrottles(actors);
            return;
        }
        long gameTime = subLevel.getLevel().getGameTime();
        Quaterniondc orientation = subLevel.logicalPose().orientation();
        Vector3dc centerOfMass = mass.getCenterOfMass();
        double inverseMass = 1.0 / mass.getMass();
        Matrix3dc inverseInertia = mass.getInverseInertiaTensor();
        Matrix3dc inertia = mass.getInertiaTensor();
        // Radius of gyration: turns angular acceleration into a comparable linear one.
        double radius = Math.max(1.0, Math.sqrt((inertia.m00() + inertia.m11() + inertia.m22()) * inverseMass * 0.5));

        // What each thruster does at full power, in the ship's own frame.
        int n = actors.length;
        double[][] columns = new double[n][ThrustAllocator.ROWS];
        Vector3d sideways = new Vector3d();
        double sidewaysTotal = 0.0;
        double normTotal = 0.0;
        Vector3d torque = new Vector3d();
        Vector3d angular = new Vector3d();
        for (int i = 0; i < n; i++) {
            ThrustControl.Sample sample = actors[i] == null ? null : ThrustControl.freshSample(actors[i], gameTime);
            if (sample == null) {
                continue;
            }
            Vector3dc force = sample.force;
            // Torque about the centre of mass: sum(p x f) - c x sum(f).
            torque.set(sample.moment).sub(centerOfMass.cross(force, new Vector3d()));
            inverseInertia.transform(torque, angular).mul(radius);
            double[] column = columns[i];
            column[0] = force.x() * inverseMass;
            column[1] = force.y() * inverseMass * VERTICAL_WEIGHT;
            column[2] = force.z() * inverseMass;
            column[3] = angular.x;
            column[4] = angular.y;
            column[5] = angular.z;
            sideways.add(force.x(), 0.0, force.z());
            sidewaysTotal += Math.hypot(force.x(), force.z());
            for (double v : column) {
                normTotal += v * v;
            }
        }

        // Linear: velocity toward the goal (zero = hover), acceleration toward that velocity, plus beating gravity.
        Vector3dc target = goal;
        Vector3d here = worldCenter(subLevel, new Vector3d());
        Vector3d desiredVelocity = new Vector3d();
        if (target != null) {
            desiredVelocity.set(target).sub(here);
            double distance = desiredVelocity.length();
            if (distance < DEAD_ZONE) {
                desiredVelocity.zero();
            } else {
                desiredVelocity.mul(Math.min(MAX_SPEED, distance * POSITION_GAIN) / distance);
            }
        }
        Vector3d acceleration = new Vector3d(desiredVelocity).sub(handle.getLinearVelocity(new Vector3d())).mul(VELOCITY_GAIN);
        clampLength(acceleration, MAX_ACCEL);
        acceleration.sub(DimensionPhysicsData.getGravity(subLevel.getLevel()));
        Vector3d localAcceleration = orientation.transformInverse(acceleration, new Vector3d());

        // Angular: level out, and face the goal if the thrusters mostly push one way.
        Vector3d up = orientation.transform(new Vector3d(0.0, 1.0, 0.0));
        Vector3d desiredSpin = up.cross(0.0, 1.0, 0.0, new Vector3d()).mul(LEVEL_GAIN);
        if (target != null && sidewaysTotal > 0.0 && sideways.length() > FORWARD_BIAS * sidewaysTotal) {
            double gx = target.x() - here.x;
            double gz = target.z() - here.z;
            Vector3d forward = orientation.transform(new Vector3d(sideways));
            if (gx * gx + gz * gz > FACE_DISTANCE * FACE_DISTANCE && forward.x * forward.x + forward.z * forward.z > 1.0E-6) {
                // Signed angle about +Y from forward to the goal.
                double heading = Math.atan2(forward.z * gx - forward.x * gz, forward.x * gx + forward.z * gz);
                desiredSpin.y += Mth.clamp(heading * YAW_GAIN, -MAX_YAW_RATE, MAX_YAW_RATE);
            }
        }
        Vector3d angularAcceleration = desiredSpin.sub(handle.getAngularVelocity(new Vector3d())).mul(ANGULAR_GAIN);
        clampLength(angularAcceleration, MAX_ANGULAR_ACCEL);
        Vector3d localAngular = orientation.transformInverse(angularAcceleration, new Vector3d()).mul(radius);

        double[] wanted = {
                localAcceleration.x, localAcceleration.y * VERTICAL_WEIGHT, localAcceleration.z,
                localAngular.x, localAngular.y, localAngular.z
        };
        if (throttles.length != n) {
            throttles = new double[n];
        }
        ThrustAllocator.solve(columns, wanted, throttles, 1.0E-3 * normTotal / n + 1.0E-9);
        for (int i = 0; i < n; i++) {
            if (actors[i] != null) {
                ThrustControl.setThrottle(actors[i], throttles[i]);
            }
        }
    }

    private static void clampLength(Vector3d v, double max) {
        double length = v.length();
        if (length > max) {
            v.mul(max / length);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) {
            CLIENT_BRAINS.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            CLIENT_BRAINS.remove(this);
        } else {
            released = false;
            releaseThrottles(thrusters);
        }
    }

    // ---------------------------------------------------------------- menu

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.lost_in_the_sky.ship_brain");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ShipBrainMenu(containerId, inventory, this, data);
    }

    // ---------------------------------------------------------------- saving and sync

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("mode", mode.name());
        tag.putInt("follow_distance", followDistance);
        if (followPlayer != null) {
            tag.putUUID("follow_player", followPlayer);
        }
        if (stayPos != null) {
            tag.putDouble("stay_x", stayPos.x);
            tag.putDouble("stay_y", stayPos.y);
            tag.putDouble("stay_z", stayPos.z);
        }
        writeLinks(tag);
    }

    private void writeLinks(CompoundTag tag) {
        ListTag list = new ListTag();
        for (BlockPos pos : links) {
            list.add(NbtUtils.writeBlockPos(pos));
        }
        tag.put("links", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("mode")) {
            try {
                mode = Mode.valueOf(tag.getString("mode"));
            } catch (IllegalArgumentException e) {
                mode = Mode.OFF;
            }
            followDistance = tag.contains("follow_distance")
                    ? Mth.clamp(tag.getInt("follow_distance"), MIN_DISTANCE, MAX_DISTANCE)
                    : DEFAULT_DISTANCE;
            followPlayer = tag.hasUUID("follow_player") ? tag.getUUID("follow_player") : null;
            stayPos = tag.contains("stay_x")
                    ? new Vector3d(tag.getDouble("stay_x"), tag.getDouble("stay_y"), tag.getDouble("stay_z"))
                    : null;
            if (mode == Mode.STAY && stayPos == null || mode == Mode.FOLLOW && followPlayer == null) {
                mode = Mode.OFF;
            }
        }
        List<BlockPos> loaded = new ArrayList<>();
        ListTag list = tag.getList("links", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size() && loaded.size() < MAX_LINKS; i++) {
            if (list.get(i) instanceof IntArrayTag array && array.size() == 3) {
                int[] xyz = array.getAsIntArray();
                loaded.add(new BlockPos(xyz[0], xyz[1], xyz[2]));
            }
        }
        links = List.copyOf(loaded);
        goal = null;
    }

    /** Clients only need the links (for the wrench lines). */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        writeLinks(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
