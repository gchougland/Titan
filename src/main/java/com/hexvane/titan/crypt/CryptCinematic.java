package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.CameraKeyframe;
import com.hypixel.hytale.protocol.CameraSequenceFlags;
import com.hypixel.hytale.protocol.EasingType;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.modules.camera.CameraKeyframeBuilder;
import com.hypixel.hytale.server.core.modules.camera.CameraSequenceBuilder;
import com.hypixel.hytale.server.core.modules.camera.CameraSequencePacketHandler;
import com.hypixel.hytale.server.core.modules.camera.CameraSequenceSource;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.concurrent.ConcurrentHashMap;

/** Native client-interpolated tracks, including a synchronized remaining track for late arrivals. */
public final class CryptCinematic {
    private static final ConcurrentHashMap<PlayerRef, OwnedSequence> OWNED = new ConcurrentHashMap<>();
    private static final double SAMPLE_STEP = 1.0 / 30;

    private CryptCinematic() { }

    public static boolean play(ComponentAccessor<EntityStore> access, Ref<EntityStore> ref, CryptArena arena, boolean death) {
        return play(access, ref, arena, death, 0);
    }

    /** elapsed is seconds since this INTRO/DYING phase started, not time since the player arrived. */
    public static boolean play(ComponentAccessor<EntityStore> access, Ref<EntityStore> ref, CryptArena arena,
                               boolean death, double elapsed) {
        if (ref == null || !ref.isValid()) return false;
        var player = access.getComponent(ref, PlayerRef.getComponentType());
        var transform = access.getComponent(ref, TransformComponent.getComponentType());
        if (player == null || transform == null) return false;
        var camera = handler(player);
        if (camera == null) return false;
        var head = access.getComponent(ref, HeadRotation.getComponentType());
        var look = head == null ? transform.getRotation() : head.getRotation();
        var track = timeline(arena, new Vector3d(transform.getPosition()).add(0, 1.65, 0), look, death, elapsed);
        if (track.keyframeCount() == 0) return false;
        var owned = new OwnedSequence(player, track);
        OWNED.put(player, owned);
        camera.begin(owned);
        return true;
    }

    /** Keeps every original authored waypoint, easing, and duration unchanged for on-time viewers. */
    static CameraSequenceSource timeline(CryptArena arena, Vector3d eye, Rotation3f look, boolean death, double elapsed) {
        var full = authored(arena, eye, look, death);
        if (!Double.isFinite(elapsed)) return new CameraSequenceBuilder().build();
        if (elapsed <= 0) return full;
        CameraKeyframe[] frames = new CameraKeyframe[full.keyframeCount()];
        full.writeKeyframes(0, frames.length, frames);
        double total = 0;
        for (var frame : frames) total += frame.duration;
        if (elapsed >= total) return new CameraSequenceBuilder().build();

        var result = lockedTrack();
        double segmentStart = 0;
        int segment = 0;
        while (segment < frames.length - 1 && segmentStart + frames[segment].duration <= elapsed) {
            segmentStart += frames[segment++].duration;
        }
        var from = frames[Math.max(0, segment - 1)];
        var to = frames[segment];
        double segmentEnd = segmentStart + to.duration;
        // A short initial keyframe places a late viewer at the current point. Its duration
        // counts toward the remaining segment, so joining never delays the global ending.
        double next = Math.min(segmentEnd, elapsed + .01);
        result.keyframe(sample(from, to, (next - segmentStart) / to.duration, next - elapsed));
        // Restarting SineInOut for a shortened segment changes both its path timing and speed.
        // Sample only this partial segment at 30 Hz; all following segments remain native.
        while (next < segmentEnd - 1e-8) {
            double end = Math.min(segmentEnd, next + SAMPLE_STEP);
            result.keyframe(sample(from, to, (end - segmentStart) / to.duration, end - next));
            next = end;
        }
        for (int i = segment + 1; i < frames.length; i++) result.keyframe(copy(frames[i]));
        return result.build();
    }

    private static CameraSequenceSource authored(CryptArena a, Vector3d eye, Rotation3f look, boolean death) {
        var track = lockedTrack().keyframe(eye, look, .01f, EasingType.Linear);
        if (death) {
            track.keyframeLookingAt(a.point(-12, 13, 36), a.point(0, 12, 0), 1.7f, EasingType.SineInOut)
                .keyframeLookingAt(a.point(12, 15, 33), a.point(0, 7, 0), 4.3f, EasingType.SineInOut)
                .keyframeLookingAt(a.point(3, 9, 39), a.rewardPoint(), 2.4f, EasingType.SineInOut)
                .keyframe(eye, look, 1.1f, EasingType.SineInOut);
        } else {
            track.keyframeLookingAt(a.camera(), a.point(0, 4, 4), 2.7f, EasingType.SineInOut)
                .keyframeLookingAt(a.point(-4, 13, 42), a.point(0, 3, 0), 3.8f, EasingType.SineInOut)
                .keyframeLookingAt(a.point(6, 16, 38), a.point(0, 12, 0), 4.3f, EasingType.SineInOut)
                .keyframeLookingAt(a.point(3, 14, 36), a.point(0, 12, 0), 1.7f, EasingType.SineInOut)
                .keyframe(eye, look, 1.2f, EasingType.SineInOut);
        }
        return track.build();
    }

    private static CameraSequenceBuilder lockedTrack() {
        return new CameraSequenceBuilder().addFlag(CameraSequenceFlags.LockInput)
            .addFlag(CameraSequenceFlags.ReturnToGameplayCameraOnEnd);
    }

    private static CameraKeyframeBuilder sample(CameraKeyframe from, CameraKeyframe to, double progress, double duration) {
        double u = Math.max(0, Math.min(1, progress));
        double weight = to.easing == EasingType.SineInOut ? (1 - Math.cos(Math.PI * u)) * .5 : u;
        var position = position(from).lerp(position(to), weight);
        var look = Rotation3f.lerpAngle(rotation(from), rotation(to), (float) weight, new Rotation3f());
        return new CameraKeyframeBuilder((float) duration, EasingType.Linear).position(position).look(look);
    }

    private static Vector3d position(CameraKeyframe frame) {
        return new Vector3d(frame.position.x, frame.position.y, frame.position.z);
    }

    private static Rotation3f rotation(CameraKeyframe frame) {
        return frame.look != null ? new Rotation3f(frame.look.pitch, frame.look.yaw, frame.look.roll)
            : Rotation3f.lookAt(position(frame), new Vector3d(frame.lookAtPoint.x, frame.lookAtPoint.y, frame.lookAtPoint.z));
    }

    private static CameraKeyframeBuilder copy(CameraKeyframe frame) {
        var builder = new CameraKeyframeBuilder(frame.duration, frame.easing).position(position(frame));
        return frame.look != null ? builder.look(rotation(frame))
            : builder.lookAt(new Vector3d(frame.lookAtPoint.x, frame.lookAtPoint.y, frame.lookAtPoint.z));
    }

    public static void restore(ComponentAccessor<EntityStore> access, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return;
        var player = access.getComponent(ref, PlayerRef.getComponentType());
        if (player == null) return;
        var transform = access.getComponent(ref, TransformComponent.getComponentType());
        var head = access.getComponent(ref, HeadRotation.getComponentType());
        restore(player, transform, head);
    }

    private static void restore(PlayerRef player, TransformComponent transform, HeadRotation head) {
        OWNED.remove(player);
        var camera = handler(player);
        if (camera == null) return;
        var frame = new CameraKeyframeBuilder(.05f, EasingType.Linear);
        if (transform != null) frame.position(new Vector3d(transform.getPosition()).add(0, 1.65, 0))
            .look(head == null ? transform.getRotation() : head.getRotation());
        camera.begin(new CameraSequenceBuilder().addFlag(CameraSequenceFlags.ReturnToGameplayCameraOnEnd)
            .keyframe(frame).build());
    }

    private static CameraSequencePacketHandler handler(PlayerRef player) {
        return player.getPacketHandler() instanceof GenericPacketHandler generic
            ? generic.getSubPacketHandler(CameraSequencePacketHandler.class) : null;
    }

    static void restoreOnRemoval(Holder<EntityStore> holder) {
        var player = holder.getComponent(PlayerRef.getComponentType());
        if (player == null || !OWNED.containsKey(player)) return;
        restore(player, holder.getComponent(TransformComponent.getComponentType()), holder.getComponent(HeadRotation.getComponentType()));
    }

    /** The connection survives world transfer, but its old entity ref is already invalid here. */
    public static final class PlayerRemoval extends HolderSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public void onEntityAdd(Holder<EntityStore> holder, AddReason reason, Store<EntityStore> store) { }
        @Override public void onEntityRemoved(Holder<EntityStore> holder, RemoveReason reason, Store<EntityStore> store) {
            restoreOnRemoval(holder);
        }
    }

    private static final class OwnedSequence implements CameraSequenceSource {
        private final PlayerRef player;
        private final CameraSequenceSource source;
        private OwnedSequence(PlayerRef player, CameraSequenceSource source) { this.player = player; this.source = source; }
        @Override public byte flags() { return source.flags(); }
        @Override public Float baseFov() { return source.baseFov(); }
        @Override public int keyframeCount() { return source.keyframeCount(); }
        @Override public void writeKeyframes(int fromIndex, int count, CameraKeyframe[] out) { source.writeKeyframes(fromIndex, count, out); }
        @Override public void onComplete(PlayerRef ref) { OWNED.remove(player, this); }
    }
}
