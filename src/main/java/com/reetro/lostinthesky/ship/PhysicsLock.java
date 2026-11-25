package com.reetro.lostinthesky.ship;

import com.reetro.lostinthesky.LostInTheSky;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;

/**
 * Whether a ship is locked in place with Create Aeronautics' Physics Staff. Aeronautics (mod id {@code simulated})
 * isn't on our compile classpath (it's nested inside the Aeronautics jar), so this goes through reflection:
 * {@code PhysicsStaffServerHandler.get(level).isLocked(subLevel)}. If that ever breaks, ships count as unlocked.
 */
final class PhysicsLock {
    private static final String HANDLER = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler";

    private static boolean resolved;
    private static Method get;
    private static Method isLocked;

    private PhysicsLock() {
    }

    static boolean isLocked(ServerLevel level, SubLevel subLevel) {
        if (!resolve()) {
            return false;
        }
        try {
            Object handler = get.invoke(null, level);
            return handler != null && (boolean) isLocked.invoke(handler, subLevel);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LostInTheSky.LOGGER.warn("Could not read the Physics Staff lock; ignoring it from now on", e);
            get = null;
            return false;
        }
    }

    private static synchronized boolean resolve() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> handler = Class.forName(HANDLER);
                get = handler.getMethod("get", ServerLevel.class);
                isLocked = handler.getMethod("isLocked", SubLevel.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                LostInTheSky.LOGGER.warn("Physics Staff lock not found; ship brains will ignore it", e);
                get = null;
            }
        }
        return get != null;
    }
}
