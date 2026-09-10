package com.hexvane.titan.crypt;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Deterministic fight rules, independent of the engine and presentation. Times are seconds. */
public final class CryptFight {
    public enum State { INTRO, REST, ATTACK, FALLING, STUNNED, RECOVERING, DYING, FINISHED }
    public enum Move { SWEEP_LEFT, SWEEP_RIGHT, SLAM, MINIONS, BLUE_FIRE, POISON, BEAM, GRAB }
    public static final double INTRO_SECONDS = 14;
    public static final double DEATH_SECONDS = 10;
    public static final float BRACELET_CROWN_DAMAGE_FRACTION = .03f;
    public float maxCrown, maxBracelet;
    private final float soloCrown, soloBracelet;
    private int partySize;
    private float crown;
    private final float[] bracelets = new float[2];
    private final Map<Long, Long> hitTicks = new HashMap<>();
    private final Random random;
    private State state = State.INTRO;
    private Move move, previous;
    private double time;
    private int revision;

    public CryptFight(long seed, float healthScale) {
        this(seed, healthScale, 1);
    }
    public CryptFight(long seed, float healthScale, int players) {
        random = new Random(seed);
        soloCrown = 2600 * Math.max(.1f, healthScale);
        soloBracelet = 340 * Math.max(.1f, healthScale);
        partySize = Math.max(1, players);
        maxCrown = soloCrown * partyMultiplier(partySize);
        maxBracelet = soloBracelet * partyMultiplier(partySize);
        crown = maxCrown;
        bracelets[0] = bracelets[1] = maxBracelet;
    }
    public int partySize() { return partySize; }
    private static float partyMultiplier(int count) { return 1 + (count - 1) * .55f; }
    /** Keep the largest simultaneous group; joining cannot erase damage, stuns or a final blow. */
    public boolean growPartyTo(int players) {
        if (players <= partySize || state == State.DYING || state == State.FINISHED) return false;
        double crownFraction = crown / (double) maxCrown;
        double leftFraction = bracelets[0] / (double) maxBracelet;
        double rightFraction = bracelets[1] / (double) maxBracelet;
        partySize = players;
        maxCrown = soloCrown * partyMultiplier(partySize);
        maxBracelet = soloBracelet * partyMultiplier(partySize);
        crown = (float) (maxCrown * crownFraction);
        bracelets[0] = (float) (maxBracelet * leftFraction);
        bracelets[1] = (float) (maxBracelet * rightFraction);
        return true;
    }
    public State state() { return state; }
    public Move move() { return move; }
    public double time() { return time; }
    public int revision() { return revision; }
    public float crown() { return crown; }
    public float bracelet(int side) { return bracelets[side]; }
    public int phase() { return crown > maxCrown * .66f ? 1 : crown > maxCrown * .33f ? 2 : 3; }
    public boolean damageable() { return state != State.INTRO && state != State.DYING && state != State.FINISHED; }
    public boolean collidableArms() { return state != State.DYING && state != State.FINISHED &&
        !(state == State.ATTACK && (move == Move.SWEEP_LEFT || move == Move.SWEEP_RIGHT || move == Move.GRAB)); }
    public double windup() {
        if (move == null) return 0;
        return switch (move) {
            case SWEEP_LEFT, SWEEP_RIGHT -> 1.65;
            case SLAM -> 1.8;
            case MINIONS -> 2.2;
            case BLUE_FIRE -> 2;
            case POISON -> 2.1;
            case BEAM -> 2.6;
            case GRAB -> 1.9;
        };
    }
    public double activeDuration() {
        if (move == null) return 0;
        return switch (move) {
            case SWEEP_LEFT, SWEEP_RIGHT -> 1.2;
            case SLAM -> .4;
            case MINIONS, BLUE_FIRE -> .6;
            case POISON -> 2;
            case BEAM -> 4.5;
            case GRAB -> 2;
        };
    }
    public double stunDuration() { return phase() == 1 ? 12 : phase() == 2 ? 10.5 : 9; }
    public double restDuration() { return phase() == 1 ? 3.4 : phase() == 2 ? 2.8 : 2.25; }
    public void tick(double dt) {
        if (!Double.isFinite(dt) || dt < 0) return;
        time += Math.min(dt, .25); // lag cannot skip a telegraph or the entire damage window
        switch (state) {
            case INTRO -> { if (time >= INTRO_SECONDS) enter(State.REST); }
            case REST -> {
                if (time >= restDuration()) {
                    Move[] choices = phase() == 1
                        ? new Move[] { Move.SWEEP_LEFT, Move.SWEEP_RIGHT, Move.SLAM, Move.MINIONS }
                        : phase() == 2
                        ? new Move[] { Move.SWEEP_LEFT, Move.SWEEP_RIGHT, Move.SLAM, Move.BLUE_FIRE, Move.POISON, Move.MINIONS }
                        : new Move[] { Move.SWEEP_LEFT, Move.SWEEP_RIGHT, Move.SLAM, Move.BLUE_FIRE, Move.POISON, Move.MINIONS, Move.BEAM };
                    do { move = choices[random.nextInt(choices.length)]; } while (move == previous);
                    previous = move;
                    enter(State.ATTACK);
                }
            }
            case ATTACK -> { if (time >= windup() + activeDuration() + 1.3) enter(State.REST); }
            case FALLING -> { if (time >= 2.2) enter(State.STUNNED); }
            case STUNNED -> { if (time >= stunDuration()) enter(State.RECOVERING); }
            case RECOVERING -> {
                if (time >= 3) {
                    bracelets[0] = bracelets[1] = maxBracelet;
                    enter(State.REST);
                }
            }
            case DYING -> { if (time >= DEATH_SECONDS) enter(State.FINISHED); }
            case FINISHED -> { }
        }
    }
    /** Reactive retaliation is scheduled only between attacks, preserving dodge and crown windows. */
    public boolean requestGrab() {
        if (state != State.REST || time < 1.1) return false;
        move = Move.GRAB; previous = move; enter(State.ATTACK); return true;
    }
    /** pool 0 = crown; 1/2 = bracelets. Multiple struck voxels of one pool count once per attacker/tick. */
    public boolean hit(int pool, float amount, int attacker, long tick) {
        if (!damageable() || !Float.isFinite(amount) || amount <= 0 || pool < 0 || pool > 2) return false;
        if (pool > 0 && (bracelets[pool - 1] <= 0 || state == State.RECOVERING)) return false;
        long key = ((long) attacker << 32) ^ pool;
        if (hitTicks.getOrDefault(key, Long.MIN_VALUE) == tick) return false;
        if (hitTicks.size() > 128) hitTicks.entrySet().removeIf(e -> e.getValue() < tick - 2);
        hitTicks.put(key, tick);
        if (pool == 0) {
            crown = Math.max(0, crown - amount);
            if (crown == 0) enter(State.DYING);
        } else {
            bracelets[pool - 1] = Math.max(0, bracelets[pool - 1] - amount);
            if (bracelets[pool - 1] == 0) {
                crown = Math.max(0, crown - maxCrown * BRACELET_CROWN_DAMAGE_FRACTION);
                if (crown == 0) enter(State.DYING);
                else if (bracelets[0] == 0 && bracelets[1] == 0) enter(State.FALLING);
            }
        }
        return true;
    }
    private void enter(State next) { state = next; time = 0; revision++; }
    public static double smooth(double a) { a = Math.clamp(a, 0, 1); return a * a * (3 - 2 * a); }
}
