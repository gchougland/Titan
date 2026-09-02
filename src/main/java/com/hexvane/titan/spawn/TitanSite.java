package com.hexvane.titan.spawn;

import javax.annotation.Nonnull;

/**
 * Decides where titans could stand, from nothing but the world seed and a grid cell.
 *
 * <p>The world is cut into fixed cells, and a cell's seed alone fixes the spot within it, whether it is
 * occupied, and which variant stands there. Nothing is rolled when a player walks up, so a titan is the
 * same titan when they come back, two players approaching from opposite sides agree on what they see, and
 * the whole candidate list can be re-derived after a restart without any of it being saved.
 *
 * <p>Cells are large and sites are inset from their edges, so two neighbouring titans can never end up
 * within sight of each other however the rolls land.
 */
public final class TitanSite {

    /**
     * Edge length of one cell, in blocks. At most one titan stands in a cell, so this is the density knob:
     * titans per unit area is a rule's chance over this squared.
     *
     * <p>Larger than the roughly 190 blocks the world is simulated out to around a player, so a player
     * standing still may have no site in reach and a titan is something walked into rather than something
     * waiting at the edge of every view. Much larger again and few walks would cross a cell at all.
     */
    public static final int CELL_BLOCKS = 256;

    /**
     * How far a site is kept from its cell's edges, in blocks. Without it two titans in adjacent cells
     * could land back to back on the shared border.
     *
     * <p>This, not the cell size, sets how close two titans can ever get: sites in neighbouring cells are
     * at worst twice this apart however the rolls land, so raising the cell size alone only thins them out
     * on average. Kept well under half the cell, or every site lands near its cell's centre and the grid
     * becomes visible.
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

        /** @return which way the titan faces, in radians. */
        public float yaw() {
            return yaw;
        }

        /** @return roll compared against a rule's chance to decide whether this cell holds a titan. */
        public double occupancy() {
            return occupancy;
        }

        /** @return roll feeding the rule's weighted variant pick. */
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

    /** Fills {@code out} with the site this cell would hold. Pure: the same inputs always give the same site. */
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
