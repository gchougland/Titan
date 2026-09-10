package com.hexvane.titan.crypt;

import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import com.google.gson.stream.JsonReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CryptArenaTest {
    @Test void importedMarkerPlacesCoffinAndEntranceAtAuthoredWorldCoordinates() {
        var arena = new CryptArena(new Vector3d(-114.5,103,-.5));
        assertEquals(new Vector3i(-86,105,0), arena.coffinBlock());
        assertVector(new Vector3d(0,159,0), arena.prefabOrigin());
        assertVector(new Vector3d(.5,160,.5), arena.entrance());
        assertVector(new Vector3d(-85.5,106.2,0), arena.rewardPoint());
        assertEquals(1.2, arena.rewardPoint().y - arena.coffinBlock().y, .00001);
        assertVector(new Vector3d(-103.5,103,-13.5), arena.grabs().get(0));
        assertVector(new Vector3d(-114.5,88,-.5), arena.pitBottom());
    }

    @Test void combatMarkersAreInRoomAndSurfaceVisitorsAreExcluded() {
        var arena = new CryptArena(new Vector3d(100,80,100));
        assertEquals(2, arena.grabs().size());
        assertEquals(28, arena.flames().size());
        assertEquals(7, arena.minions().size());
        arena.grabs().forEach(p -> assertTrue(arena.contains(p)));
        arena.flames().forEach(p -> assertTrue(arena.contains(p)));
        arena.minions().forEach(p -> assertTrue(arena.contains(p)));
        assertTrue(arena.contains(arena.coffin()));
        assertTrue(arena.contains(arena.pitBottom()));
        assertFalse(arena.contains(arena.entrance()));
        assertFalse(arena.contains(arena.center().add(0,56,0)));
        assertFalse(arena.contains(arena.point(46,0,20)));
    }

    @Test void everyPackagedRoomAirCellFitsForAllDungeonRotations() throws Exception {
        var arenas = new CryptArena[4];
        for (int rotation = 0; rotation < arenas.length; rotation++)
            arenas[rotation] = new CryptArena(new Vector3d(123.5, 89, 456.5), (float) (rotation * Math.PI / 2));
        int roomAir = 0;
        try (var input = getClass().getResourceAsStream("/Server/Prefabs/Titan/Crypt/SkeletonDungeon_Site.prefab.json")) {
            assertNotNull(input);
            try (var reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if (!reader.nextName().equals("blocks")) { reader.skipValue(); continue; }
                    reader.beginArray();
                    while (reader.hasNext()) {
                        int x = 0, y = 0, z = 0; String name = "";
                        reader.beginObject();
                        while (reader.hasNext()) switch (reader.nextName()) {
                            case "x" -> x = reader.nextInt() + 64;
                            case "y" -> y = reader.nextInt() + 74;
                            case "z" -> z = reader.nextInt() - 2;
                            case "name" -> name = reader.nextString();
                            default -> reader.skipValue();
                        }
                        reader.endObject();
                        if (!name.equals("Empty") || x > 7 || y > 46) continue;
                        roomAir++;
                        for (var arena : arenas) {
                            if (!arena.contains(arena.sourcePoint(x + .5, y, z + .5)))
                                fail("Authored room air excluded at source " + x + "," + y + "," + z + "; yaw=" + arena.yaw());
                        }
                    }
                    reader.endArray();
                }
                reader.endObject();
            }
        }
        assertTrue(roomAir > 100000, "Validate the actual full room and pit, rather than a few markers");
    }

    @Test void outerCornersAndAlcovesHaveLeewayWithoutAdmittingTheSurfaceOrUpperStairs() {
        // Supported two-block air positions read from the packaged dungeon, including cells
        // outside the old x=6 / z=36 room limits and decorative pockets on all four walls.
        int[][] corners = {{-65,9,-40}, {-65,5,36}, {4,18,-40}, {4,18,36},
            {-66,19,-34}, {-59,19,-41}, {-59,19,37}, {7,19,-7}};
        for (int rotation = 0; rotation < 4; rotation++) {
            var arena = new CryptArena(new Vector3d(123.5,89,456.5), (float) (rotation * Math.PI / 2));
            for (int[] cell : corners) for (double dx : new double[]{-1.5, 0, 1.5})
                for (double dz : new double[]{-1.5, 0, 1.5})
                    assertTrue(arena.contains(arena.sourcePoint(cell[0] + .5 + dx, cell[1], cell[2] + .5 + dz)));
            assertFalse(arena.contains(arena.entrance()));
            assertFalse(arena.contains(arena.point(0, 56, 0)), "Players directly above the boss remain outside");
            assertFalse(arena.contains(arena.sourcePoint(25, 45, -2)), "Upper stairs are outside the encounter");
            assertFalse(arena.contains(arena.sourcePoint(0, 75, 0)), "Surface visitors cannot prevent a wipe");
        }
    }

    @Test void rotatingArenaKeepsTelegraphsCoffinAndCameraInSameFrame() {
        var arena = new CryptArena(new Vector3d(123.5,89,456.5), (float)(Math.PI/2));
        assertVector(new Vector3d(4,7,13), arena.local(arena.point(4,7,13)));
        assertEquals(127.5, arena.point(4,7,13).x, .00001);
        assertEquals(443.5, arena.point(4,7,13).z, .00001);
        assertTrue(arena.contains(arena.camera()));
        assertTrue(arena.contains(arena.cameraTarget()));
        assertTrue(arena.contains(arena.stunnedHead()));
        assertEquals(3.2, arena.local(arena.rewardPoint()).y, .00001);
    }

    private static void assertVector(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, .00001);
        assertEquals(expected.y, actual.y, .00001);
        assertEquals(expected.z, actual.z, .00001);
    }
}
