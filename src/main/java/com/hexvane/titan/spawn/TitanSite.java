package com.hexvane.titan.spawn;

import javax.annotation.Nonnull;

/**
 * Decides where titans could stand, from nothing but the world seed and a grid cell.
 *
 * <p>Nothing about a site is rolled at the moment a player walks up to it. The world is cut into fixed
 * cells, and a cell's seed alone fixes the spot within it, whether it is occupied at all, and which variant
 * stands there. That means a titan a player walked away from is the same titan when they come back, and two
 * players approaching from opposite sides agree on what they are looking at, without any of it having to be
 * saved. It also means the whole candidate list can be re-derived after a restart.
 *
 * <p>Cells are large and sites are inset from their edges, so two neighbouring titans can never end up
 * within sight of each other however the rolls land.
 */
public final class TitanSite {

    /**
     * Edge length of one cell, in blocks. At most one titan stands in a cell.
     *
     * <p>This is the density knob: a cell holds at most one titan, so how many there are per acre is the
     * rule's chance over this squared. It is deliberately larger than the roughly 190 blocks the world is
     * simulated out to around a player, which means a player standing still may have no site in reach at
     * all. That is the intent — a titan should be something walked into rather than something waiting at
     * the edge of every view — but it is also the floor: cells very much larger than this and the odds of
     * a given walk crossing one at all stop being worth the rule existing.
     */
    public static final int CELL_BLOCKS = 256;

    /**
     * How far a site is kept from its cell's edges, in blocks. Without it two titans in adjacent cells
     * could land back to back on the shared border.
     *
     * <p>This, and not the cell size, is what sets how close two titans can ever get: sites in neighbouring
     * cells are at worst twice this apart, however the rolls land, so raising the cell size alone thins
     * them out on average while leaving the awkward close pairs exactly as close as they were. Kept well
     * under half the cell, or every titan ends up near its cell's centre and the grid becomes visible.
     */
    private static final int CELL_MARGIN = 64;

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private TitanSite() {
    }

    /** Everything the siting pass needs to know about one cell. */
    public static final class Roll {
        private double x;
        private double z;
        private float yaw;
        private double occupancy;
        private double variant;

        public double x() {
            return x;
        }

        public double z() {
            return z;
        }

        /** Which way the titan faces, in radians. */
        public float yaw() {
            return yaw;
        }

        /** Compared against a rule's chance to decide whether this cell holds a titan at all. */
        public double occupancy() {
            return occupancy;
        }

        /** Feeds the rule's weighted variant pick. */
        public double variant() {
            return variant;
        }
    }

    public static int cellOf(final double world) {
        return Math.floorDiv((int) Math.floor(world), CELL_BLOCKS);
    }

    public static long cellKey(final int cellX, final int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    /** Fills {@code out} with the site this cell would hold. Pure: the same inputs always agree. */
    public static void roll(final long worldSeed, final int cellX, final int cellZ, @Nonnull final Roll out) {
        long state = mix(worldSeed ^ mix(cellKey(cellX, cellZ)));

        final int span = CELL_BLOCKS - CELL_MARGIN * 2;
        state = mix(state + GOLDEN);
        out.x = (double) cellX * CELL_BLOCKS + CELL_MARGIN + unit(state) * span;
        state = mix(state + GOLDEN);
        out.z = (double) cellZ * CELL_BLOCKS + CELL_MARGIN + unit(state) * span;
        state = mix(state + GOLDEN);
        out.yaw = (float) (unit(state) * Math.PI * 2.0);
        state = mix(state + GOLDEN);
        out.occupancy = unit(state);
        state = mix(state + GOLDEN);
        out.variant = unit(state);
    }

    /** SplitMix64 finaliser: cheap, and its avalanche is good enough that adjacent cells look unrelated. */
    private static long mix(final long input) {
        long z = input + GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Top 53 bits as a double in {@code [0,1)}, the same way {@code Random.nextDouble} does it. */
    private static double unit(final long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }
}
