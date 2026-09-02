package com.hexvane.titan.anim;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.util.BsonUtil;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Reads Blockbench {@code .blockyanim} files into {@link TitanBoneTrack}s.
 *
 * <p>The server's own {@code BlockyAnimationCache} only reads {@code duration}, because clips are
 * evaluated client-side. Titans are posed on the server, so the keyframes are parsed here instead. The
 * schema is the stock one: {@code duration} in 60fps frames, then {@code nodeAnimations[bone].position}
 * (xyz deltas) and {@code nodeAnimations[bone].orientation} (xyzw quaternions). Files therefore stay
 * editable in Blockbench against a stand-in model whose node names match the titan's bone names.
 */
public final class BlockyAnimParser {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Keyframe times in a {@code .blockyanim} are frame indices at this rate. */
    private static final double FRAMES_PER_SECOND = 60.0;

    private BlockyAnimParser() {
    }

    /** A parsed file before it is bound to a particular skeleton; tracks are keyed by source node name. */
    public record ParsedAnimation(float duration, boolean holdLastKeyframe, @Nonnull Map<String, TitanBoneTrack> tracks) {
    }

    /**
     * Loads and parses a clip from the common asset packs.
     *
     * @param name path under {@code Common/}, e.g. {@code Titan/Talus/Animations/Walk.blockyanim}
     * @return {@code null} when the asset is missing or malformed; the caller falls back to the bind pose
     */
    @Nullable
    public static ParsedAnimation load(@Nonnull final String name) {
        final var asset = CommonAssetRegistry.getByName(name);
        if (asset == null) {
            LOGGER.at(Level.WARNING).log("Titan animation '%s' not found in any common asset pack", name);
            return null;
        }

        try {
            final byte[] bytes = asset.getBlob().join();
            return parse(name, new String(bytes, StandardCharsets.UTF_8));
        } catch (final Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Failed to read Titan animation '%s'", name);
            return null;
        }
    }

    /**
     * Parses clip JSON that has already been read into memory.
     *
     * @param name label used in log messages only
     * @return {@code null} when the document is not valid JSON
     */
    @Nullable
    public static ParsedAnimation parse(@Nonnull final String name, @Nonnull final String json) {
        final BsonDocument root;
        try {
            root = BsonUtil.parseWithMaxDepth(json);
        } catch (final Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Titan animation '%s' is not valid JSON", name);
            return null;
        }

        final float duration = (float) (number(root.get("duration"), 0) / FRAMES_PER_SECOND);
        final boolean hold = root.containsKey("holdLastKeyframe") && root.get("holdLastKeyframe").isBoolean()
            && root.getBoolean("holdLastKeyframe").getValue();

        final var tracks = new HashMap<String, TitanBoneTrack>();
        final BsonValue nodes = root.get("nodeAnimations");
        if (nodes != null && nodes.isDocument()) {
            for (final var entry : nodes.asDocument().entrySet()) {
                if (!entry.getValue().isDocument()) continue;
                final var track = readTrack(entry.getValue().asDocument());
                if (track != null) tracks.put(entry.getKey(), track);
            }
        }

        if (tracks.isEmpty()) {
            LOGGER.at(Level.FINE).log("Titan animation '%s' has no node animations", name);
        }
        return new ParsedAnimation(duration, hold, tracks);
    }

    @Nullable
    private static TitanBoneTrack readTrack(@Nonnull final BsonDocument node) {
        final BsonArray positions = array(node, "position");
        final BsonArray orientations = array(node, "orientation");
        if (positions.isEmpty() && orientations.isEmpty()) return null;

        final float[] posTimes = new float[positions.size()];
        final float[] posValues = new float[positions.size() * 3];
        for (int i = 0; i < positions.size(); i++) {
            final var key = positions.get(i).asDocument();
            posTimes[i] = frameToSeconds(key);
            final BsonValue delta = key.get("delta");
            final BsonDocument d = delta != null && delta.isDocument() ? delta.asDocument() : new BsonDocument();
            posValues[i * 3] = (float) number(d.get("x"), 0);
            posValues[i * 3 + 1] = (float) number(d.get("y"), 0);
            posValues[i * 3 + 2] = (float) number(d.get("z"), 0);
        }

        final float[] rotTimes = new float[orientations.size()];
        final float[] rotValues = new float[orientations.size() * 4];
        for (int i = 0; i < orientations.size(); i++) {
            final var key = orientations.get(i).asDocument();
            rotTimes[i] = frameToSeconds(key);
            final BsonValue delta = key.get("delta");
            final BsonDocument d = delta != null && delta.isDocument() ? delta.asDocument() : new BsonDocument();
            rotValues[i * 4] = (float) number(d.get("x"), 0);
            rotValues[i * 4 + 1] = (float) number(d.get("y"), 0);
            rotValues[i * 4 + 2] = (float) number(d.get("z"), 0);
            rotValues[i * 4 + 3] = (float) number(d.get("w"), 1);
        }

        return new TitanBoneTrack(posTimes, posValues, rotTimes, rotValues);
    }

    private static float frameToSeconds(@Nonnull final BsonDocument key) {
        return (float) (number(key.get("time"), 0) / FRAMES_PER_SECOND);
    }

    @Nonnull
    private static BsonArray array(@Nonnull final BsonDocument node, @Nonnull final String key) {
        final BsonValue value = node.get(key);
        return value != null && value.isArray() ? value.asArray() : new BsonArray();
    }

    private static double number(@Nullable final BsonValue value, final double fallback) {
        return value != null && value.isNumber() ? value.asNumber().doubleValue() : fallback;
    }
}
