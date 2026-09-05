package com.hexvane.titan.yaga;

import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * What a player is telling their house to do with the wand in their hand.
 *
 * <p>The house is never driven, only pointed at somewhere. Holding either mouse button sends it walking
 * the way the wand is pointing for as long as the button is down, and the use key sends it over whatever
 * is in front of it. Nothing about the player changes while any of that is happening: they keep their own
 * body, their own camera and their own controls, which is the whole reason this exists — every attempt at
 * putting the player <em>in</em> the house ended in the two of them arguing about where the house was.
 *
 * <p>This class is the one place that knows a player is pointing. It is told by the wand's own
 * interactions — {@link YagaPointInteraction} while a button is held, {@link YagaLeapInteraction} on the
 * use key — which run on the interaction chain rather than in any system, and which know nothing about
 * houses. What they say is recorded here and read back on the next tick by {@link YagaPetSystem}, which is
 * already looking up each house's owner and is the only thing allowed to move one. Keyed by account rather
 * than by entity because the player is a different entity after every logout while the wand and the house
 * are the same.
 *
 * <p>A hold has to be ended by something, and the end can go missing — a disconnect mid-press, a client
 * that swallows the release while a window opens over it. Three things end one, so none of them has to be
 * reliable on its own: the interaction finishing, the wand leaving the player's hand, and {@link
 * #HOLD_TIMEOUT} passing without the hold being renewed. The worst case is a house that walks a moment too
 * long, rather than one that walks away forever.
 */
public final class YagaWand {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Asset id of the wand item, which is also the file it is declared in. */
    public static final String ITEM = "Titan_Yaga_Wand";

    /**
     * How long a hold stands for without being renewed.
     *
     * <p>A held interaction is renewed every time the client says it is still holding, which is often, so
     * this is not a limit on how long a house can be walked — it is how long after the client goes quiet
     * the house keeps going. Long enough to ride out a stutter, short enough that a player who crashes
     * mid-press does not come back to a house on the far side of the valley.
     */
    private static final long HOLD_TIMEOUT = TimeUnit.SECONDS.toNanos(2);

    /** How long an unclaimed leap waits to be picked up, in case its house is not ticking this second. */
    private static final long LEAP_TIMEOUT = TimeUnit.SECONDS.toNanos(2);

    @Nonnull
    private static final Map<UUID, YagaWand> HANDS = new ConcurrentHashMap<>();

    private volatile boolean pointing;
    private volatile long heard;
    private volatile long leapt;

    private YagaWand() {
    }

    /**
     * Starts, or renews, a hold.
     *
     * <p>Called by {@link YagaPointInteraction} for as long as a button is down. Both buttons do the same
     * thing, and deliberately: a player pointing at where they want the house to go should not have to
     * remember which hand they are pointing with.
     */
    static void point(@Nonnull final UUID uuid) {
        final YagaWand hand = HANDS.computeIfAbsent(uuid, ignored -> new YagaWand());
        final boolean started = !hand.pointing;

        hand.heard = System.nanoTime();
        hand.pointing = true;

        if (started) log("pointing", uuid);
    }

    /** Ends a hold, keeping any leap that has not been picked up yet. */
    static void release(@Nonnull final UUID uuid) {
        final YagaWand hand = HANDS.get(uuid);
        if (hand == null || !hand.pointing) return;

        hand.pointing = false;
        if (hand.leapt == 0) HANDS.remove(uuid, hand);

        log("released", uuid);
    }

    /**
     * Asks for a leap, from the use key.
     *
     * <p>Called by {@link YagaLeapInteraction}, which is what the wand's use key runs. Recorded rather than
     * acted on because a leap is a thing a house does and the house is not known here; the tick that finds
     * it picks this up.
     */
    static void leap(@Nonnull final UUID uuid) {
        final YagaWand hand = HANDS.computeIfAbsent(uuid, ignored -> new YagaWand());
        hand.leapt = System.nanoTime();

        log("leap", uuid);
    }

    /** Whether this player is holding a button down with the wand right now. */
    public static boolean isPointing(@Nonnull final UUID uuid) {
        final YagaWand hand = HANDS.get(uuid);
        if (hand == null || !hand.pointing) return false;

        if (System.nanoTime() - hand.heard <= HOLD_TIMEOUT) return true;

        release(uuid);
        return false;
    }

    /**
     * Takes this player's outstanding leap, if they asked for one recently.
     *
     * <p>Consumed rather than read, so one press is one leap however many houses a player owns and however
     * often the asking system runs.
     */
    public static boolean consumeLeap(@Nonnull final UUID uuid) {
        final YagaWand hand = HANDS.get(uuid);
        if (hand == null || hand.leapt == 0) return false;

        final boolean fresh = System.nanoTime() - hand.leapt <= LEAP_TIMEOUT;
        hand.leapt = 0;
        if (!hand.pointing) HANDS.remove(uuid, hand);
        return fresh;
    }

    /** Forgets a player, so a logout does not leave their last press behind for their next session. */
    public static void forget(@Nullable final UUID uuid) {
        if (uuid != null) HANDS.remove(uuid);
    }

    /** Whether the wand is the thing this player is holding. */
    public static boolean inHand(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                 @Nonnull final Ref<EntityStore> player) {

        final var held = InventoryComponent.getItemInHand(accessor, player);
        if (held == null || held.isEmpty()) return false;

        final var item = held.getItem();
        return item != null && ITEM.equals(item.getId());
    }

    /**
     * Says what the wand was told, once per thing rather than once per tick.
     *
     * <p>A wand that is not working and a wand that is not being pointed anywhere look identical from in
     * front of the house, since it stands still either way. Nothing in the log means the press is not
     * reaching the server; a line means the house heard and had its own reasons.
     */
    private static void log(@Nonnull final String what, @Nonnull final UUID uuid) {
        if (TitanConfig.get().isWandLogEnabled()) LOGGER.at(Level.INFO).log("wand: %s by %s", what, uuid);
    }
}
