package com.hexvane.titan.combat;

import com.hexvane.titan.dunewyrm.DunewyrmTerrain;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.system.PlayerVelocityInstructionSystem;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/** Regression checks against actual engine queues, client packets and loaded terrain sections. */
public final class TitanApiRuntimeSmoke {
    private TitanApiRuntimeSmoke() { }

    public static void run(Store<EntityStore> store, Ref<EntityStore> player,
                           List<ChangeVelocity> packets, Vector3d ground) {
        var velocity = store.ensureAndGetComponent(player, Velocity.getComponentType());
        velocity.getInstructions().clear();
        packets.clear();
        var scratch = new Vector3d(18, 9, 24);
        store.forEachChunk(Velocity.getComponentType(), (chunk, cb) -> {
            for (int i = 0; i < chunk.size(); i++) if (player.equals(chunk.getReferenceTo(i))) {
                TitanImpulse.set(cb, player, scratch);
                scratch.set(-3, .12, 4);
                TitanImpulse.set(cb, player, scratch);
                scratch.zero();
            }
        });
        check(velocity.getInstructions().size() == 2, "two independent impulses queued");
        check(velocity.getInstructions().getFirst().getType() == ChangeVelocityType.Set, "throw sets velocity");
        check(velocity.getInstructions().getFirst().getVelocity().equals(new Vector3d(18, 9, 24)),
            "reused attack vectors cannot rewrite an earlier victim's impulse");
        deliver(store, player);
        check(packets.size() == 2, "engine emits velocity client packets");
        check(close(packets.getFirst().x, 18) && close(packets.getFirst().y, 9)
            && close(packets.getFirst().z, 24), "30-block-per-second throw reaches client without scaling");
        check(close(packets.getLast().x, -3) && close(packets.getLast().y, .12)
            && close(packets.getLast().z, 4), "small sideways contact shove retains its units");
        check(velocity.getInstructions().isEmpty(), "velocity instructions consumed once");
        deliver(store, player);
        check(packets.size() == 2, "next tick does not resend a throw");

        store.forEachChunk(Velocity.getComponentType(), (chunk, cb) -> {
            for (int i = 0; i < chunk.size(); i++) if (player.equals(chunk.getReferenceTo(i)))
                TitanSmashAttack.impulse(cb, player, new Vector3d(300, 90, 400));
        });
        deliver(store, player);
        var capped = packets.getLast();
        check(close(Math.hypot(capped.x, capped.z), 12) && close(capped.y, 8),
            "smash preserves horizontal and vertical safety caps");
        packets.clear();

        terrain(store, ground);
        System.out.println("[TITAN API SMOKE] independent impulses / client packet speeds / one-shot delivery / smash caps / section-boundary surface sampling / terrain preserved PASS");
    }

    private static void deliver(Store<EntityStore> store, Ref<EntityStore> player) {
        var system = new PlayerVelocityInstructionSystem();
        store.forEachChunk(system.getQuery(), (chunk, cb) -> {
            for (int i = 0; i < chunk.size(); i++) if (player.equals(chunk.getReferenceTo(i)))
                system.tick(.05f, i, chunk, store, cb);
        });
    }

    private record Saved(int x, int y, int z, int id, int rotation, int filler) { }

    private static void terrain(Store<EntityStore> store, Vector3d ground) {
        var chunks = store.getExternalData().getWorld().getChunkStore();
        int x = (int) ground.x, y = ((int) ground.y / 32 + 1) * 32 - 1, z = (int) ground.z;
        int stone = BlockType.getAssetMap().getIndex("Rock_Basalt");
        var saved = new ArrayList<Saved>();
        try {
            for (int dy = -1; dy <= 3; dy++) place(chunks, saved, x, y + dy, z, stone);
            place(chunks, saved, x + 2, y, z, stone);
            place(chunks, saved, x, y, z - 4, stone);
            place(chunks, saved, x, y, z - 5, stone);
            var head = new Vector3d(x + .5, y, z + .5);
            check(DunewyrmTerrain.surface(chunks,head.x,head.z)>=y+4,"surface probe chooses roof above cave");
            check(!DunewyrmTerrain.clearHead(chunks,head.x,head.y,head.z),"solid wall prevents movement");
            for (int dy=-1;dy<=3;dy++) check(GroundSampler.blockId(chunks,x,y+dy,z)==stone,"terrain remains intact");
            check(!GroundSampler.isValid(DunewyrmTerrain.surface(chunks,1000000,1000000)),"unloaded columns do not trigger generation");
        } finally {
            for (var b : saved) BlockOperations.setBlock(chunks,
                chunks.getChunkSectionReferenceAtBlock(b.x, b.y, b.z), b.x, b.y, b.z, b.id,
                BlockType.getAssetMap().getAsset(b.id), b.rotation, b.filler, SetBlockSettings.NONE);
        }
    }

    private static void place(ChunkStore chunks, List<Saved> saved, int x, int y, int z, int id) {
        var ref = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        check(ref != null, "terrain fixture section loaded");
        var blocks = chunks.getStore().getComponent(ref, BlockSection.getComponentType());
        saved.add(new Saved(x, y, z, blocks.get(x, y, z), blocks.getRotationIndex(x, y, z), blocks.getFiller(x, y, z)));
        BlockOperations.setBlock(chunks, ref, x, y, z, id, BlockType.getAssetMap().getAsset(id),
            RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER, SetBlockSettings.NONE);
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < .0001; }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
