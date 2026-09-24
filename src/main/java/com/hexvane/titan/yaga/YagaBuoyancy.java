package com.hexvane.titan.yaga;

import com.hexvane.titan.ai.TitanBodyDriver;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3d;

/** Keeps the walking floor near the water, only when standing would submerge that floor. */
public final class YagaBuoyancy {
    public static final double FREEBOARD = .15;
    public record Support(double rootY, boolean swimming) { }
    private YagaBuoyancy() { }

    public static Support support(ChunkStore chunks, Vector3d position, double platformHeight, double sink) {
        double ground = GroundSampler.sample(chunks, position.x, position.y + sink, position.z, 6, 16);
        double water = platformHeight > 0 ? waterSurface(chunks, position, platformHeight + sink) : Double.NaN;
        if (Double.isFinite(water)) {
            // The bottom can be far below the ordinary walking probe once the house is afloat.
            ground = GroundSampler.sample(chunks, position.x, water, position.z, 0,
                (int) Math.ceil(water) - ChunkUtil.MIN_Y);
        }
        return chooseSupport(ground, water, platformHeight, sink);
    }

    public static Support chooseSupport(double ground, double water, double platformHeight, double sink) {
        if (platformHeight > 0 && Double.isFinite(ground) && Double.isFinite(water)
            && water > ground + platformHeight) {
            return new Support(water + FREEBOARD - platformHeight, true);
        }
        return new Support(ground - sink, false);
    }

    public static void settle(ChunkStore chunks, TitanComponent titan, TransformComponent transform, float dt, double sink) {
        var variant = titan.getVariant();
        double height = variant == null ? 0 : variant.getFloatPlatformHeight() * titan.getScale();
        var support = support(chunks, transform.getPosition(), height, sink);
        titan.setSwimming(support.swimming());
        if (support.swimming()) titan.setFootSink(0);
        if (!Double.isFinite(support.rootY())) return;
        double delta = support.rootY() - transform.getPosition().y;
        double step = TitanBodyDriver.BODY_HEIGHT_FOLLOW * dt;
        transform.getPosition().y += Math.clamp(delta, -step, step);
    }

    /** Follow connected water upward; never float on lava or an unrelated pool above a roof. */
    private static double waterSurface(ChunkStore chunks, Vector3d p, double height) {
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        int start = Math.max(ChunkUtil.MIN_Y, (int) Math.floor(p.y));
        int end = Math.min(ChunkUtil.HEIGHT_MINUS_1, (int) Math.ceil(p.y + height));
        double surface = Double.NaN;
        boolean found = false;
        for (int y = start; y <= ChunkUtil.HEIGHT_MINUS_1; y++) {
            if (!found && y > end) break;
            var ref = chunks.getChunkSectionReferenceAtBlock(x, y, z);
            if (ref == null) return Double.NaN;
            var section = chunks.getStore().getComponent(ref, FluidSection.getComponentType());
            var fluid = section == null ? null : section.getFluid(x, y, z);
            boolean water = fluid != null && isWater(fluid.getId()) && section.getFluidLevel(x, y, z) > 0;
            if (water) {
                found = true;
                surface = y + Math.clamp((double) section.getFluidLevel(x, y, z) / fluid.getMaxFluidLevel(), 0, 1);
            } else if (found) {
                return surface;
            } else if (y > p.y + 1 && GroundSampler.isGround(chunks, x, y, z)) {
                return Double.NaN;
            }
        }
        return surface;
    }

    public static boolean isWater(String id) {
        return "Water".equals(id) || (id != null && id.startsWith("Water_"));
    }
}
