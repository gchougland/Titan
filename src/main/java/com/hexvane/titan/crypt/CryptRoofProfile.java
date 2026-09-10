package com.hexvane.titan.crypt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.IntBinaryOperator;

/** Complete authored and repaired roof footprint, independent of chunk order and biome boundaries. */
final class CryptRoofProfile {
    static final CryptRoofProfile AUTHORED = load();
    static final int COVER_BLOCKS = 1;
    record Column(int x, int z, int top) {}
    private final Column[] columns;

    CryptRoofProfile(Column... columns) {
        this.columns = columns.clone();
        var unique = new HashSet<Long>();
        for (Column column : columns) {
            if (column.top >= 0 || !unique.add(((long) column.x << 32) ^ (column.z & 0xffffffffL))) {
                throw new IllegalArgumentException("Invalid underground roof column: " + column);
            }
        }
    }

    boolean fits(int anchorX, int anchorY, int anchorZ, IntBinaryOperator terrainHeight) {
        for (Column column : columns) {
            if (terrainHeight.applyAsInt(anchorX + column.x, anchorZ + column.z)
                    < anchorY + column.top + COVER_BLOCKS) return false;
        }
        return true;
    }

    Column[] columns() { return columns.clone(); }

    private static CryptRoofProfile load() {
        var stream = CryptRoofProfile.class.getResourceAsStream("/Titan/Crypt/RoofColumns.csv");
        if (stream == null) throw new IllegalStateException("Missing Crypt Keeper underground roof profile");
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            var columns = new ArrayList<Column>();
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] values = line.split(",");
                if (values.length != 3) throw new IOException("Invalid roof profile row");
                columns.add(new Column(Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2])));
            }
            if (columns.size() != 6679) throw new IOException("Incomplete crypt roof profile");
            return new CryptRoofProfile(columns.toArray(Column[]::new));
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("Invalid Crypt Keeper underground roof profile", exception);
        }
    }
}
