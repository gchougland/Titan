package com.hexvane.titan.compat;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.combat.TitanCoreSafety;
import com.hexvane.titan.combat.TitanEncounterScale;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LevelingCompatibilityTest {
    @Test void endlessHealthIsGentlerWithoutChangingRpgOrStackingBothMods() {
        var config = new TitanConfig();
        assertEquals(1.25, LevelingCompatibility.fromLevels(1, 51, config).health(), .00001);
        assertEquals(1.5, LevelingCompatibility.fromLevels(1, 101, config).health(), .00001);
        assertEquals(2, LevelingCompatibility.fromLevels(1, Integer.MAX_VALUE, config).health());
        assertEquals(2.25, LevelingCompatibility.fromLevels(51, 101, config).health(), .00001);
        assertEquals(LevelingCompatibility.Scaling.NONE, LevelingCompatibility.fromLevels(1, Double.NaN, config));
        assertEquals(LevelingCompatibility.fromLevel(51, config), LevelingCompatibility.fromLevels(51, 1, config));
    }
    @Test void noLevelDataRetainsOriginalBalance() {
        var config = new TitanConfig();
        for (double level : new double[]{-1, 0, 1, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(LevelingCompatibility.Scaling.NONE, LevelingCompatibility.fromLevel(level, config));
    }
    @Test void averageLevelScalesHealthAndDamageSeparately() {
        var scale = LevelingCompatibility.fromLevel(51, new TitanConfig());
        assertEquals(2.25, scale.health(), .00001);
        assertEquals(1.5, scale.damage(), .00001);
    }
    @Test void endlessLevelsCannotCreateUnboundedMultipliers() {
        var scale = LevelingCompatibility.fromLevel(Integer.MAX_VALUE, new TitanConfig());
        assertEquals(10, scale.health()); assertEquals(4, scale.damage());
    }
    @Test void absentAndOutdatedApisAreSafe() {
        UUID uuid = UUID.randomUUID();
        var missing = new LevelingCompatibility.Provider("not.installed.LevelingAPI", false);
        assertEquals(0, missing.level(uuid, null, null));
        assertEquals(0, missing.level(uuid, null, null));
        assertEquals(0, new LevelingCompatibility.Provider(String.class.getName(), true).level(uuid, null, null));
    }
    @Test void rpgUsesWorldThreadPlayerAndStoreOverload() {
        var bridge = new LevelingCompatibility.Provider(RpgFixture.class.getName(), true);
        assertEquals(47, bridge.level(UUID.randomUUID(), null, null));
    }
    @Test void endlessUsesUuidAndObtainsSingletonAfterStartup() {
        var bridge = new LevelingCompatibility.Provider(EndlessFixture.class.getName(), false);
        var uuid = UUID.randomUUID(); EndlessFixture.expected = uuid; EndlessFixture.available = false;
        assertEquals(0, bridge.level(uuid, null, null));
        EndlessFixture.available = true;
        assertEquals(88, bridge.level(uuid, null, null));
    }
    @Test void wavesScaleForGroupsAndRetainSoloCounts() {
        assertEquals(3, TitanEncounterScale.minionCount(3, 0));
        assertEquals(3, TitanEncounterScale.minionCount(3, 1));
        assertEquals(5, TitanEncounterScale.minionCount(3, 2));
        assertEquals(6, TitanEncounterScale.minionCount(3, 3));
        assertEquals(16, TitanEncounterScale.minionCount(8, 3));
        assertEquals(64, TitanEncounterScale.minionCount(8, 1000));
        assertEquals(0, TitanEncounterScale.minionCount(0, 5));
    }
    @Test void detectsBodiesInsideFeetAndLegs() {
        var half = new Vector3d(4, 2.5, 4);
        var core = new TitanCoreSafety.Volume(0, new Vector3d(), half);
        for (var feet : new Vector3d[]{new Vector3d(), new Vector3d(-3, -2, 1), new Vector3d(1, 0, -3)}) {
            assertTrue(TitanCoreSafety.overlaps(feet, .4, 1.8, new org.joml.Matrix4d(), core));
        }
        assertTrue(TitanCoreSafety.overlaps(new Vector3d(4.1, 0, 0), .4, 1.8, new org.joml.Matrix4d(), core),
            "body can overlap even when the feet center is outside");
    }
    @Test void ridersBystandersAndPlayersBeneathRaisedFeetAreNotTeleported() {
        var half = new Vector3d(4, 2.5, 4);
        var core = new TitanCoreSafety.Volume(0, new Vector3d(), half);
        assertFalse(TitanCoreSafety.overlaps(new Vector3d(0, 2.5, 0), .4, 1.8, new org.joml.Matrix4d(), core));
        assertFalse(TitanCoreSafety.overlaps(new Vector3d(4.5, 0, 0), .4, 1.8, new org.joml.Matrix4d(), core));
        assertFalse(TitanCoreSafety.overlaps(new Vector3d(0, -5, 0), .4, 1.8, new org.joml.Matrix4d(), core));
    }
    public static final class RpgFixture {
        public static RpgFixture get() { return new RpgFixture(); }
        public Info getPlayerLevelInfo(PlayerRef player, Store<EntityStore> store) { return new Info(); }
        public int getPlayerLevel(UUID ignored) { throw new AssertionError("Holder-only API must not be used"); }
    }
    public static final class Info { public int getLevel() { return 47; } }
    public static final class EndlessFixture {
        static boolean available; static UUID expected;
        public static EndlessFixture get() { return available ? new EndlessFixture() : null; }
        public int getPlayerLevel(UUID uuid) { assertEquals(expected, uuid); return 88; }
    }
}
