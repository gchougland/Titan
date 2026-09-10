package com.hexvane.titan.combat;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Last-resort escape when a moving solid limb overtakes a player between client collision updates. */
public final class TitanCoreSafety {
    public record Volume(int bone, Vector3d center, Vector3d halfSize) { }
    private record Bounds(Vector3d center, Vector3d half, Vector3d[] axes) { }
    private TitanCoreSafety() { }

    /** Test the upright player body against a posed limb, including pitch, roll and titan scale. */
    public static boolean overlaps(Vector3d feet, double radius, double height, Matrix4d world, Volume core) {
        return overlaps(feet, radius, height, bounds(world, core));
    }

    private static Bounds bounds(Matrix4d world, Volume core) {
        var axes = new Vector3d[]{world.transformDirection(new Vector3d(1, 0, 0)),
            world.transformDirection(new Vector3d(0, 1, 0)), world.transformDirection(new Vector3d(0, 0, 1))};
        var half = new Vector3d(core.halfSize()).mul(axes[0].length(), axes[1].length(), axes[2].length());
        for (var axis : axes) axis.normalize();
        return new Bounds(world.transformPosition(new Vector3d(core.center())), half, axes);
    }

    private static boolean overlaps(Vector3d feet, double radius, double height, Bounds core) {
        // A small vertical inset leaves a player standing on a surface alone despite contact rounding.
        var playerHalf = new Vector3d(radius, Math.max(.01, height * .5 - .06), radius);
        var delta = new Vector3d(feet).add(0, height * .5, 0).sub(core.center());
        var worldAxes = new Vector3d[]{new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)};
        // Separating-axis test: three player axes, three limb axes, and nine cross-product axes.
        for (var axis : worldAxes) if (separated(delta, playerHalf, core, axis)) return false;
        for (var axis : core.axes()) {
            if (separated(delta, playerHalf, core, axis)) return false;
            for (var playerAxis : worldAxes) {
                var cross = new Vector3d(axis).cross(playerAxis);
                if (cross.lengthSquared() > 1e-12 && separated(delta, playerHalf, core, cross)) return false;
            }
        }
        return true;
    }

    private static boolean separated(Vector3d delta, Vector3d playerHalf, Bounds core, Vector3d axis) {
        double playerRadius = playerHalf.x * Math.abs(axis.x) + playerHalf.y * Math.abs(axis.y)
            + playerHalf.z * Math.abs(axis.z);
        double limbRadius = core.half().x * Math.abs(core.axes()[0].dot(axis))
            + core.half().y * Math.abs(core.axes()[1].dot(axis))
            + core.half().z * Math.abs(core.axes()[2].dot(axis));
        return Math.abs(delta.dot(axis)) >= playerRadius + limbRadius - 1e-7;
    }

    private static boolean clearOfLimbs(Vector3d feet, double radius, double height, List<Bounds> cores) {
        for (var core : cores) if (overlaps(feet, radius, height, core)) return false;
        return true;
    }

    private static Vector3d findGround(ChunkStore chunks, Vector3d feet, double radius, double height,
                                       List<Bounds> cores, Vector3d hub, double scale) {
        double outward = Math.atan2(feet.z - hub.z, feet.x - hub.x);
        int reach = (int)Math.ceil(Math.min(64, Math.max(16, 12 * scale)));
        int below = (int)Math.ceil(Math.min(256, Math.max(64, 48 * scale)));
        // Prefer the nearest clear ground, searching outward from the Temple before trying its interior.
        for (int distance = 1; distance <= reach; distance++) {
            for (int sample = 0; sample < 16; sample++) {
                int side = (sample + 1) / 2 * (sample % 2 == 0 ? -1 : 1);
                double angle = outward + side * Math.PI / 8;
                double x = feet.x + Math.cos(angle) * distance, z = feet.z + Math.sin(angle) * distance;
                double ground = GroundSampler.sample(chunks, x, feet.y, z, 2, below);
                if (!GroundSampler.isValid(ground)) continue;
                var safe = new Vector3d(x, ground + .15, z);
                if (clearOfLimbs(safe, radius + .3, height, cores) && clearTerrain(chunks, safe, radius, height))
                    return safe;
            }
        }
        return null;
    }

    /** Require loaded, empty space for the whole body, with solid ground under its center. */
    private static boolean clearTerrain(ChunkStore chunks, Vector3d feet, double radius, double height) {
        for (int x = (int)Math.floor(feet.x - radius); x <= (int)Math.floor(feet.x + radius); x++) {
            for (int z = (int)Math.floor(feet.z - radius); z <= (int)Math.floor(feet.z + radius); z++) {
                for (int y = (int)Math.floor(feet.y); y <= (int)Math.floor(feet.y + height); y++) {
                    int id = GroundSampler.blockId(chunks, x, y, z);
                    if (id == BlockType.UNKNOWN_ID || GroundSampler.isSolid(chunks, x, y, z)) return false;
                }
            }
        }
        return true;
    }

    public static void rescue(TitanComponent titan, CommandBuffer<EntityStore> cb) {
        if (titan.getSolidCores().isEmpty() || titan.getPose() == null
            || titan.getState() == com.hexvane.titan.entity.TitanState.DYING) return;
        var players = cb.getExternalData().getWorld().getPlayerRefs();
        if (players.isEmpty()) return;
        var cores = new ArrayList<Bounds>(titan.getSolidCores().size());
        for (var core : titan.getSolidCores()) cores.add(bounds(titan.getPose().getWorld(core.bone()), core));
        var hub = titan.getPose().getWorld(0).transformPosition(new Vector3d());
        for (var player : players) {
            var ref = player.getReference();
            if (ref == null || !ref.isValid() || cb.getComponent(ref, DeathComponent.getComponentType()) != null
                || cb.getComponent(ref, Teleport.getComponentType()) != null) continue;
            var tx = cb.getComponent(ref, TransformComponent.getComponentType());
            if (tx == null) continue;
            var box = cb.getComponent(ref, BoundingBox.getComponentType());
            double radius = .4, height = 1.8;
            if (box != null) {
                var b = box.getBoundingBox();
                radius = Math.max(Math.max(Math.abs(b.min.x), Math.abs(b.max.x)),
                    Math.max(Math.abs(b.min.z), Math.abs(b.max.z)));
                height = b.max.y - b.min.y;
            }
            if (clearOfLimbs(tx.getPosition(), radius, height, cores)) continue;
            var safe = findGround(cb.getExternalData().getWorld().getChunkStore(), tx.getPosition(), radius,
                height, cores, hub, titan.getScale());
            if (safe != null) {
                cb.putComponent(ref, Teleport.getComponentType(), new Teleport(safe, tx.getRotation()));
            }
        }
    }
}
