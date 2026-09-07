package com.hexvane.titan.dunewyrm;

import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Ring buffer of head samples used so body pieces trail the head along its path.
 *
 * <p>Sample yaw is the path tangent (direction toward the head), not the head's historical facing, so
 * trailing segments bank with the curve instead of spinning the wrong way on a turn.
 */
public final class DunewyrmPathBuffer {

    private final double[] x;
    private final double[] y;
    private final double[] z;
    private int count;
    private int head;

    public DunewyrmPathBuffer(final int capacity) {
        this.x = new double[capacity];
        this.y = new double[capacity];
        this.z = new double[capacity];
    }

    public void clear() {
        count = 0;
        head = 0;
    }

    public void push(final double px, final double py, final double pz, final float ignoredYaw) {
        if (count > 0) {
            final int newest = index(count - 1);
            final double dx = px - x[newest];
            final double dy = py - y[newest];
            final double dz = pz - z[newest];
            if (dx * dx + dy * dy + dz * dz < 0.0001) {
                x[newest] = px;
                y[newest] = py;
                z[newest] = pz;
                return;
            }
        }

        if (count < x.length) {
            final int i = (head + count) % x.length;
            x[i] = px;
            y[i] = py;
            z[i] = pz;
            count++;
            return;
        }

        x[head] = px;
        y[head] = py;
        z[head] = pz;
        head = (head + 1) % x.length;
    }

    private int index(final int n) {
        return (head + n) % x.length;
    }

    public int size() {
        return count;
    }

    /**
     * Samples the path at {@code distance} behind the newest point. {@code outYaw} is the tangent facing
     * along the path toward the head (Hytale movement yaw: forward is {@code (-sin, -cos)}).
     */
    public boolean sampleBehind(final double distance, @Nonnull final Vector3d out, @Nonnull final float[] outYaw) {
        if (count == 0) return false;
        if (count == 1 || distance <= 0) {
            final int i = index(count - 1);
            out.set(x[i], y[i], z[i]);
            outYaw[0] = tangentYaw(count - 1);
            return true;
        }

        double remaining = distance;
        for (int n = count - 1; n >= 1; n--) {
            final int b = index(n);
            final int a = index(n - 1);
            final double dx = x[b] - x[a];
            final double dy = y[b] - y[a];
            final double dz = z[b] - z[a];
            final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) continue;
            if (remaining <= len) {
                final double t = 1.0 - remaining / len;
                out.set(x[a] + dx * t, y[a] + dy * t, z[a] + dz * t);
                // Face along the segment toward the newer (headward) sample.
                outYaw[0] = (float) Math.atan2(-dx, -dz);
                return true;
            }
            remaining -= len;
        }

        final int i = index(0);
        // Path too short — keep walking behind the oldest sample so the tail doesn't collapse.
        if (count >= 2) {
            final int newer = index(1);
            double dx = x[i] - x[newer];
            double dy = y[i] - y[newer];
            double dz = z[i] - z[newer];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 1e-6) {
                dx /= len;
                dy /= len;
                dz /= len;
                out.set(x[i] + dx * remaining, y[i] + dy * remaining, z[i] + dz * remaining);
                outYaw[0] = (float) Math.atan2(-(x[newer] - x[i]), -(z[newer] - z[i]));
                return true;
            }
        }
        out.set(x[i], y[i], z[i]);
        outYaw[0] = tangentYaw(0);
        return true;
    }

    private float tangentYaw(final int n) {
        if (count < 2) return 0f;
        final int from = Math.max(0, n - 1);
        final int to = Math.min(count - 1, n + 1);
        if (from == to) return 0f;
        final int a = index(from);
        final int b = index(to);
        return (float) Math.atan2(-(x[b] - x[a]), -(z[b] - z[a]));
    }

    /** Seeds a curled coil behind the head so the resting pose isn't a ruler. */
    public void seedCurl(final double hx, final double hy, final double hz, final float hyaw,
                         final double spacing, final int pieces, final float curlRadians) {
        clear();
        final double[] xs = new double[pieces + 1];
        final double[] ys = new double[pieces + 1];
        final double[] zs = new double[pieces + 1];
        xs[0] = hx;
        ys[0] = hy;
        zs[0] = hz;
        float trailYaw = hyaw;
        double cx = hx;
        double cz = hz;
        final double step = spacing * 0.92;
        final float turn = pieces <= 0 ? 0f : curlRadians / pieces;
        for (int i = 1; i <= pieces; i++) {
            trailYaw += turn;
            final double bx = -Math.sin(trailYaw);
            final double bz = -Math.cos(trailYaw);
            // Step opposite facing so the body trails behind a bending heading.
            cx -= bx * step;
            cz -= bz * step;
            xs[i] = cx;
            ys[i] = hy;
            zs[i] = cz;
        }
        for (int i = pieces; i >= 0; i--) {
            push(xs[i], ys[i], zs[i], hyaw);
        }
    }

    /** Seeds a straight chain behind a head so followers have somewhere to sit on spawn. */
    public void seedStraight(final double hx, final double hy, final double hz, final float hyaw,
                             final double spacing, final int pieces) {
        seedCurl(hx, hy, hz, hyaw, spacing, pieces, 0f);
    }
}
