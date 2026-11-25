package com.reetro.lostinthesky.ship;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.block.propeller.BlockEntitySubLevelPropellerActor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Measures and throttles the force each Sable actor (propeller, fan, ...) puts on its ship.
 *
 * <p>Sable runs every {@link BlockEntitySubLevelActor}'s physics tick from {@code ServerSubLevel#prePhysicsTick};
 * {@code mixin/ServerSubLevelMixin} marks which actor is running ({@link #begin}/{@link #end}), and
 * {@code mixin/QueuedForceGroupMixin} passes every force that actor queues through {@link #onForce}. So for any
 * block that moves a ship, from any mod, we know the force it would make at full power ({@link Sample}) and can
 * scale it by a throttle a {@link ShipBrainBlockEntity} picked. Actors without a throttle are left alone.
 */
public final class ThrustControl {
    /** Samples older than this (game ticks) count as "makes no force". */
    public static final long SAMPLE_MAX_AGE = 10;

    /** What one actor pushed with in its last physics tick, at full power, in ship-local (plot) coordinates. */
    public static final class Sample {
        /** Force, per second. */
        public final Vector3d force = new Vector3d();
        /** Sum of position x force (about the plot origin), per second. */
        public final Vector3d moment = new Vector3d();
        public long gameTime = Long.MIN_VALUE / 2; // "never", without overflowing when subtracted

        private final Vector3d pendingForce = new Vector3d();
        private final Vector3d pendingMoment = new Vector3d();
    }

    private static final Map<Object, Double> THROTTLES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Sample> SAMPLES = Collections.synchronizedMap(new WeakHashMap<>());

    private static final ThreadLocal<Sample> CURRENT_SAMPLE = new ThreadLocal<>();
    private static final ThreadLocal<Double> CURRENT_THROTTLE = new ThreadLocal<>();
    private static final ThreadLocal<Object> PENDING_ACTOR = new ThreadLocal<>();

    private ThrustControl() {
    }

    // ---------------------------------------------------------------- brain side

    public static void setThrottle(Object actor, double throttle) {
        THROTTLES.put(actor, throttle);
    }

    public static void clearThrottle(Object actor) {
        THROTTLES.remove(actor);
    }

    /** The last fresh sample for this actor, or null if it hasn't pushed recently. */
    @Nullable
    public static Sample freshSample(Object actor, long gameTime) {
        Sample sample = SAMPLES.get(actor);
        return sample != null && gameTime - sample.gameTime <= SAMPLE_MAX_AGE ? sample : null;
    }

    /** True if this actor can move a ship: a Sable propeller, or anything we've seen push a ship. */
    public static boolean canMoveShip(BlockEntitySubLevelActor actor) {
        return actor instanceof BlockEntitySubLevelPropellerActor || SAMPLES.containsKey(actor);
    }

    // ---------------------------------------------------------------- mixin side

    /** An actor's physics tick is starting. */
    public static void begin(BlockEntitySubLevelActor actor) {
        if (actor instanceof ShipBrainBlockEntity) {
            return;
        }
        Sample sample = SAMPLES.get(actor);
        Double throttle = THROTTLES.get(actor);
        CURRENT_THROTTLE.set(throttle);
        // Only start tracking actors once they push (keeps the map to things that move ships) or are throttled.
        if (sample == null && throttle != null) {
            sample = new Sample();
            SAMPLES.put(actor, sample);
        }
        if (sample != null) {
            sample.pendingForce.zero();
            sample.pendingMoment.zero();
        }
        CURRENT_SAMPLE.set(sample);
        PENDING_ACTOR.set(actor);
    }

    /** An actor's physics tick ended. */
    public static void end(double dt, long gameTime) {
        Sample sample = CURRENT_SAMPLE.get();
        if (sample != null && dt > 0.0) {
            if (sample.pendingForce.lengthSquared() > 0.0) {
                sample.force.set(sample.pendingForce).div(dt);
                sample.moment.set(sample.pendingMoment).div(dt);
                sample.gameTime = gameTime;
            } else if (gameTime - sample.gameTime > SAMPLE_MAX_AGE) {
                sample.force.zero();
                sample.moment.zero();
            }
        }
        CURRENT_SAMPLE.remove();
        CURRENT_THROTTLE.remove();
        PENDING_ACTOR.remove();
    }

    /**
     * A force (impulse for this step) queued at a plot position. Records it at full power and returns it scaled by
     * the actor's throttle; outside an actor tick it's returned unchanged.
     */
    public static Vector3dc onForce(Vector3dc position, Vector3dc force) {
        Object actor = PENDING_ACTOR.get();
        if (actor == null) {
            return force;
        }
        Sample sample = CURRENT_SAMPLE.get();
        if (sample == null && force.lengthSquared() > 0.0) {
            sample = new Sample();
            SAMPLES.put(actor, sample);
            CURRENT_SAMPLE.set(sample);
        }
        if (sample != null) {
            sample.pendingForce.add(force);
            sample.pendingMoment.add(position.cross(force, new Vector3d()));
        }
        Double throttle = CURRENT_THROTTLE.get();
        return throttle == null ? force : new Vector3d(force).mul(throttle);
    }
}
