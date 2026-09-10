package com.hexvane.titan.crypt;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CryptRoofProfileTest {
    private static final CryptRoofProfile PROFILE = CryptRoofProfile.AUTHORED;

    @Test void flatZoneOnePlateausAcceptTheCompleteAuthoredBuilding() {
        assertEquals(6679, PROFILE.columns().length);
        for (int ground : new int[]{114, 130, 160}) {
            assertTrue(PROFILE.fits(4096, ground, -4096, (x, z) -> ground));
        }
        // An underground roof one block below the entrance grade still admits flat ground.
        assertTrue(new CryptRoofProfile(new CryptRoofProfile.Column(-1, 0, -1))
            .fits(0, 130, 0, (x, z) -> 130));
        assertFalse(Arrays.stream(PROFILE.columns()).anyMatch(column -> column.x() == 0 && column.z() == 0),
            "The authored surface entrance must be allowed above ground");
    }

    @Test void isolatedValleyAtAnUnsampledRoomCornerRejectsTheWholeSite() {
        var corner = column(-120, -30);
        assertTrue(corner.top() < -20);
        // Vanilla's four plus-shaped checks miss this point entirely.
        assertFalse(PROFILE.fits(0, 130, 0,
            (x, z) -> x == corner.x() && z == corner.z() ? 130 + corner.top() : 130));
        assertTrue(PROFILE.fits(0, 130, 0,
            (x, z) -> x == corner.x() && z == corner.z() ? 131 + corner.top() : 130));
    }

    @Test void shallowStairRoofIsCheckedEvenWhenEveryRoomColumnHasDeepCover() {
        var stair = column(-30, 0);
        assertEquals(-16, stair.top());
        assertFalse(PROFILE.fits(4096, 130, -4096,
            (x, z) -> x == 4096 + stair.x() && z == -4096 + stair.z() ? 114 : 130));
    }

    @Test void everyUndergroundColumnRejectsAOneBlockHoleInItsTerrainCover() {
        // This also catches an accidental coarse sampling stride or a rectangular
        // entrance exemption that hides underground columns around the entrance.
        for (var hole : PROFILE.columns()) {
            assertFalse(PROFILE.fits(0, 130, 0,
                (x, z) -> x == hole.x() && z == hole.z() ? 130 + hole.top() : 130));
        }
    }

    @Test void restoredBoneWallsAndBasaltBackingRequireCoverWithoutExpandingIntoCorners() {
        // Source frame -> prefab frame subtracts entrance anchor (64,74,-2).
        // Both new wall planes stop at y=45: their highest affected Y is -29.
        for (int x : new int[]{-67,-68}) {
            for (int z : new int[]{-35,31}) assertEquals(-29,column(x-64,z+2).top());
        }
        for (int z : new int[]{-42,-43}) {
            for (int x : new int[]{-60,-1}) assertEquals(-29,column(x-64,z+2).top());
        }
        assertEquals(203,Arrays.stream(PROFILE.columns())
            .filter(column -> column.x() < -130 || column.z() < -39).count());
        assertFalse(Arrays.stream(PROFILE.columns()).anyMatch(column -> column.x() == -133 || column.z() == -42),
            "Terrain immediately beyond the new backing must remain outside the paste footprint");
        assertFalse(PROFILE.fits(0,130,0,(x,z) -> x == -132 && z == -33 ? 101 : 130),
            "An exposed repaired back wall must reject the placement");
    }

    private static CryptRoofProfile.Column column(int x, int z) {
        return Arrays.stream(PROFILE.columns()).filter(column -> column.x() == x && column.z() == z)
            .findFirst().orElseThrow();
    }
}
