package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import org.joml.Vector3d;

/** Runtime only: the persisted coffin is the authority for resetting and rewarding an encounter. */
public final class CryptBossComponent implements Component<EntityStore> {
    static ComponentType<EntityStore, CryptBossComponent> TYPE;
    public static ComponentType<EntityStore, CryptBossComponent> getComponentType() { return TYPE; }
    public CryptArena arena;
    public Ref<EntityStore> site;
    public CryptFight fight;
    public float levelDamageMultiplier = 1;
    public CryptRig rig;
    public final List<Ref<EntityStore>> parts = new ArrayList<>(), minions = new ArrayList<>();
    public final Set<Ref<EntityStore>> viewers = new HashSet<>(), cinematics = new HashSet<>();
    public final Set<Ref<EntityStore>> attackHits = new HashSet<>();
    public final Map<Ref<EntityStore>, Double> damageCooldown = new HashMap<>();
    public final List<Hazard> hazards = new ArrayList<>();
    public final Vector3d target = new Vector3d();
    public final Vector3d lookTarget = new Vector3d(), beamAim = new Vector3d();
    public final Vector3d aimSample = new Vector3d(), previousAttackHand = new Vector3d();
    public boolean hasLookTarget, hasPreviousAttackHand;
    public Ref<EntityStore> attackTarget;
    public double aimSampleTime, sweepOffset, hazardFxTime;
    public final Map<Ref<EntityStore>, CryptGrab.Pressure> braceletPressure = new HashMap<>();
    public Ref<EntityStore> grabbed;
    public final Vector3d grabReturn = new Vector3d();
    public int grabSide;
    public double grabCooldownUntil, grabPinTime;
    public boolean grabAttempted, grabThrown;
    public final List<Vector3d> castPoints = new ArrayList<>();
    public int revision = -1, shownPhase, lastMinionPhase;
    /** Counts attacks only: state revisions also advance on rest, stun and recovery. */
    public int attackOrdinal;
    public double elapsed, fxTime, uiTime, emptyTime, cinematicFxTime;
    public double braceletRepairTime;
    public boolean braceletRepairPending;
    public boolean fired, finishing, beamLeft;
    public int networkId;
    public record Hazard(Vector3d position, double radius, double expires, boolean poison) { }
    public CryptBossComponent() { }
    @Override public Component<EntityStore> clone() { return this; }
}
