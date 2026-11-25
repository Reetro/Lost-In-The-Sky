package com.reetro.lostinthesky.ship;

/**
 * Picks a throttle (0..1) for each thruster so their summed force and torque come as close as possible to what the
 * ship needs: bounded least squares, min |A u - b|^2 + reg |u|^2 with 0 <= u <= 1, solved by coordinate descent
 * (exact minimum along one throttle at a time, clamped). Each column of A is one thruster's force and torque at full
 * power (6 rows). Warm-started from the last throttles, so a few sweeps per physics step are plenty.
 */
final class ThrustAllocator {
    static final int ROWS = 6;
    private static final int SWEEPS = 12;

    private ThrustAllocator() {
    }

    /**
     * @param columns {@code columns[i]} is thruster i's 6 weighted rows (force xyz, torque xyz)
     * @param target  the weighted 6 rows wanted
     * @param u       throttles, read as the starting guess and overwritten with the answer
     * @param reg     small pull toward 0, so unneeded thrusters idle
     */
    static void solve(double[][] columns, double[] target, double[] u, double reg) {
        int n = columns.length;
        double[] residual = new double[ROWS]; // A u - b
        for (int r = 0; r < ROWS; r++) {
            residual[r] = -target[r];
        }
        double[] norms = new double[n];
        for (int i = 0; i < n; i++) {
            u[i] = Math.clamp(u[i], 0.0, 1.0);
            double norm = 0.0;
            for (int r = 0; r < ROWS; r++) {
                residual[r] += columns[i][r] * u[i];
                norm += columns[i][r] * columns[i][r];
            }
            norms[i] = norm;
        }
        for (int sweep = 0; sweep < SWEEPS; sweep++) {
            for (int i = 0; i < n; i++) {
                double denominator = norms[i] + reg;
                if (denominator <= 0.0) {
                    u[i] = 0.0;
                    continue;
                }
                double gradient = reg * u[i];
                for (int r = 0; r < ROWS; r++) {
                    gradient += columns[i][r] * residual[r];
                }
                double next = Math.clamp(u[i] - gradient / denominator, 0.0, 1.0);
                double delta = next - u[i];
                if (delta != 0.0) {
                    for (int r = 0; r < ROWS; r++) {
                        residual[r] += columns[i][r] * delta;
                    }
                    u[i] = next;
                }
            }
        }
    }
}
