package com.hexvane.titan.spawn;

import com.hexvane.titan.entity.TitanBrainComponent;
import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HiddenFromAdventurePlayers;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Spawns and tears down the Encounter Manager + brain NPC that drive a titan without making the body an NPC.
 *
 * <p>Must run on the world thread outside of ticking, same as {@link TitanSpawner}.
 */
public final class TitanTrio {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Encounter asset that orchestrates Talus fights (music, boss bar, role swaps). */
    public static final String ENCOUNTER_TALUS = "Titan_Talus";
    /** Brain Role while the titan is a boulder. */
    public static final String ROLE_SLEEPING = "Titan_Talus_Sleeping";
    /** Brain Role once the fight is live. */
    public static final String ROLE_COMBAT = "Titan_Talus_Combat";

    public static final String SLOT_BOSS = "Boss";
    public static final String SLOT_BRAIN = "Brain";

    private TitanTrio() {
    }

    /**
     * Attaches an Encounter Manager and invisible brain NPC to a freshly spawned titan root.
     *
     * @return {@code true} when both companions were created and bound
     */
    public static boolean attach(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final Ref<EntityStore> titanRoot) {
        final var titan = store.getComponent(titanRoot, TitanComponent.getComponentType());
        final var transform = store.getComponent(titanRoot, TransformComponent.getComponentType());
        if (titan == null || transform == null) return false;

        // Boss-bar tracking and rebind need a stable UUID on the presentation target.
        store.ensureComponent(titanRoot, UUIDComponent.getComponentType());

        final var variant = titan.getVariant();
        final String displayName = variant != null ? variant.getDisplayName() : "Titan";
        store.putComponent(titanRoot, DisplayNameComponent.getComponentType(),
            new DisplayNameComponent(Message.raw(displayName)));

        final Vector3dc position = transform.getPosition();
        final float yaw = transform.getRotation().yaw();

        final Ref<EntityStore> brain = spawnBrain(store, position, yaw, titanRoot);
        if (brain == null) {
            LOGGER.at(Level.WARNING).log("Failed to spawn titan brain NPC for variant %s", titan.getVariantId());
            return false;
        }

        final Ref<EntityStore> encounter = spawnEncounter(store, position, yaw);
        if (encounter == null) {
            LOGGER.at(Level.WARNING).log("Failed to spawn titan Encounter '%s'", ENCOUNTER_TALUS);
            store.removeEntity(brain, RemoveReason.REMOVE);
            return false;
        }

        bindSlots(store, encounter, titanRoot, brain);

        titan.setBrainDriven(true);
        titan.setBrainRef(brain);
        titan.setEncounterRef(encounter);

        // Slot maps exist only after BuilderSystem finishes. If bind landed on an empty map (race or
        // failed build), retry once the entity is fully in the store.
        if (!slotsBound(store, encounter)) {
            LOGGER.at(Level.WARNING).log(
                "Titan Encounter slots not bound after spawn (Boss/Brain); retrying on world thread");
            final var world = store.getExternalData().getWorld();
            world.execute(() -> {
                if (!encounter.isValid() || !titanRoot.isValid() || !brain.isValid()) return;
                bindSlots(store, encounter, titanRoot, brain);
                if (!slotsBound(store, encounter)) {
                    LOGGER.at(Level.SEVERE).log(
                        "Titan Encounter '%s' never registered Boss/Brain slots — Role swap and Encounter UX will not run",
                        ENCOUNTER_TALUS);
                }
            });
        }

        return true;
    }

    /** Removes brain and encounter companions; safe to call when some refs are already gone. */
    public static void detach(@Nonnull final Store<EntityStore> store, @Nonnull final TitanComponent titan) {
        final Ref<EntityStore> brain = titan.getBrainRef();
        final Ref<EntityStore> encounter = titan.getEncounterRef();
        titan.setBrainRef(null);
        titan.setEncounterRef(null);
        titan.setBrainDriven(false);
        titan.setIntent(com.hexvane.titan.entity.TitanIntent.NONE);

        if (brain != null && brain.isValid()) {
            store.removeEntity(brain, RemoveReason.REMOVE);
        }
        if (encounter != null && encounter.isValid()) {
            store.removeEntity(encounter, RemoveReason.REMOVE);
        }
    }

    @Nullable
    private static Ref<EntityStore> spawnBrain(@Nonnull final Store<EntityStore> store,
                                               @Nonnull final Vector3dc position,
                                               final float yaw,
                                               @Nonnull final Ref<EntityStore> titanRoot) {
        final int roleIndex = NPCPlugin.get().getIndex(ROLE_SLEEPING);
        if (roleIndex == AssetMapWithIndexes.NOT_FOUND) {
            LOGGER.at(Level.WARNING).log("Brain Role '%s' is not loaded", ROLE_SLEEPING);
            return null;
        }

        final var pair = NPCPlugin.get().spawnEntity(
            store,
            roleIndex,
            new Vector3d(position),
            new Rotation3f(0, yaw, 0),
            null,
            (npc, holder, s) -> {
                holder.addComponent(TitanBrainComponent.getComponentType(), new TitanBrainComponent(titanRoot));
                holder.ensureComponent(HiddenFromAdventurePlayers.getComponentType());
                holder.ensureComponent(Intangible.getComponentType());
                holder.ensureComponent(Invulnerable.getComponentType());
            },
            null);

        if (pair == null) return null;
        return pair.first();
    }

    @Nullable
    private static Ref<EntityStore> spawnEncounter(@Nonnull final Store<EntityStore> store,
                                                   @Nonnull final Vector3dc position,
                                                   final float yaw) {
        final var plugin = NPCPlugin.get();
        final int index = plugin.getIndex(ENCOUNTER_TALUS);
        if (index == AssetMapWithIndexes.NOT_FOUND) {
            LOGGER.at(Level.WARNING).log("Encounter '%s' is not loaded", ENCOUNTER_TALUS);
            return null;
        }

        final var info = plugin.getBuilderManager().tryGetBuilderInfo(index);
        if (info == null || info.getBuilder().category() != EncounterManager.class) {
            LOGGER.at(Level.WARNING).log("'%s' is not an EncounterManager asset", ENCOUNTER_TALUS);
            return null;
        }

        final var encounter = new EncounterManager(ENCOUNTER_TALUS, index);
        final var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(EncounterManager.getComponentType(), encounter);
        holder.addComponent(TransformComponent.getComponentType(),
            new TransformComponent(new Vector3d(position), new Rotation3f(0, yaw, 0)));
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.ensureComponent(HiddenFromAdventurePlayers.getComponentType());

        return store.addEntity(holder, AddReason.SPAWN);
    }

    private static void bindSlots(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final Ref<EntityStore> encounter,
                                  @Nonnull final Ref<EntityStore> titanRoot,
                                  @Nonnull final Ref<EntityStore> brain) {
        final MarkedEntitySupport marks = store.getComponent(encounter, MarkedEntitySupport.getComponentType());
        if (marks == null) {
            LOGGER.at(Level.WARNING).log("Encounter has no MarkedEntitySupport yet");
            return;
        }
        marks.setMarkedEntity(SLOT_BOSS, titanRoot, true, store);
        marks.setMarkedEntity(SLOT_BRAIN, brain, true, store);
    }

    private static boolean slotsBound(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final Ref<EntityStore> encounter) {
        final MarkedEntitySupport marks = store.getComponent(encounter, MarkedEntitySupport.getComponentType());
        if (marks == null) return false;
        return marks.hasMarkedEntityInSlot(SLOT_BOSS) && marks.hasMarkedEntityInSlot(SLOT_BRAIN);
    }

    /** Resolves the titan linked from a brain NPC, if still alive. */
    @Nullable
    public static TitanComponent linkedTitan(@Nonnull final Ref<EntityStore> brainRef,
                                             @Nonnull final Store<EntityStore> store) {
        final var brain = store.getComponent(brainRef, TitanBrainComponent.getComponentType());
        if (brain == null) return null;
        final Ref<EntityStore> root = brain.getTitanRoot();
        if (root == null || !root.isValid()) return null;
        if (store.getArchetype(root).contains(DeathComponent.getComponentType())) return null;
        return store.getComponent(root, TitanComponent.getComponentType());
    }
}
