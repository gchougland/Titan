package com.hexvane.titan.yaga;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.ProcessingBench;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Baba Yaga's furnace: the engine's own, taken off its block.
 *
 * <p>A furnace is a {@link ProcessingBenchBlock} living in a chunk section, found again by the position it
 * was placed at, and none of that suits a house that walks. What it turns out not to need, though, is the
 * block. The state object holds the three containers, the fire and the recipe, and every method that acts
 * on them is handed the position rather than looking it up — so the state is kept here instead, on the
 * house, and given the house's own position whenever it asks where it is.
 *
 * <p>That means the real furnace and not an imitation: its own window, its recipe matching, fuel quality,
 * charcoal from spent fuel, slot filters, and the catching-up it does on time it spent out of the world.
 * The one thing it does not get is the block's lit-and-smoking appearance, which it has nowhere to put.
 *
 * @see YagaFurnaceWindow
 */
public final class YagaFurnace {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Which way the furnace is taken to face, the only thing its position is used for beyond naming a
     * spot: whatever will not fit in the output tray is thrown out of the front of it.
     */
    private static final int ROTATION = RotationTuple.NONE_INDEX;

    @Nonnull
    private final ProcessingBenchBlock state;

    /**
     * Tier and the set of open windows. Never upgraded, since a bench upgrade is stored on the block and
     * this bench is thrown away with the house when it unloads.
     */
    @Nonnull
    private final BenchBlock bench = new BenchBlock();

    /**
     * A block marker pointing at nothing.
     *
     * <p>The engine's furnace uses this to mark the chunk section it lives in as needing saving. This one
     * is not in a section — it is saved with the house — and every method on the marker checks the section
     * reference first, so one built on a reference to nothing is a marker that does nothing.
     */
    @Nonnull
    private final BlockModule.BlockStateInfo detached;

    @Nonnull
    private final BlockType blockType;

    private YagaFurnace(@Nonnull final ProcessingBenchBlock state,
                        @Nonnull final BlockModule.BlockStateInfo detached,
                        @Nonnull final BlockType blockType) {

        this.state = state;
        this.detached = detached;
        this.blockType = blockType;
    }

    /**
     * Builds a furnace, either fresh or from what was saved of one.
     *
     * <p>Must run on the world thread outside of ticking: laying out the slots can eject items that no
     * longer fit, and that is an entity spawn. Restoring a furnace also runs whatever smelting it should
     * have got through while the house was away, so this is where a night's charcoal appears.
     *
     * @param saved    the state read back off disk, or {@code null} for a furnace that has never been lit
     * @param blockKey the furnace block the house is built from, which is where its recipes, slots and
     *                 fuel rules come from
     * @param position where the house is standing, for the sake of anything that falls out of it
     * @return the furnace, or {@code null} if {@code blockKey} is not a processing bench
     */
    @Nullable
    public static YagaFurnace create(@Nonnull final Store<EntityStore> store,
                                     @Nullable final ProcessingBenchBlock saved,
                                     @Nonnull final String blockKey,
                                     @Nonnull final Vector3d position) {

        final BlockType blockType = BlockType.getAssetMap().getAsset(blockKey);
        if (blockType == null || !(blockType.getBench() instanceof ProcessingBench)) {
            LOGGER.at(Level.WARNING).log(
                "Baba Yaga furnace block '%s' is not a processing bench, so it will not smelt", blockKey);
            return null;
        }

        final ProcessingBenchBlock state = saved != null ? saved : new ProcessingBenchBlock();
        if (!state.initializeBenchConfig(blockType)) return null;

        final World world = store.getExternalData().getWorld();
        final var detached = new BlockModule.BlockStateInfo(0, new Ref<>(world.getChunkStore().getStore()));
        final var furnace = new YagaFurnace(state, detached, blockType);

        state.setupSlots(world, furnace.bench, detached,
            (int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z),
            blockType, ROTATION);

        return furnace;
    }

    /** What gets written down, and what {@link #create} takes back. */
    @Nonnull
    public ProcessingBenchBlock getState() {
        return state;
    }

    /** Fuel, input and output, for deciding whether the house is worth saving again. */
    @Nonnull
    public ItemContainer[] containers() {
        return new ItemContainer[]{state.getFuelContainer(), state.getInputContainer(), state.getOutputContainer()};
    }

    /**
     * Opens the furnace in front of {@code player}.
     *
     * @param voxel    the furnace voxel, which is what the window watches to know the player is still there
     * @param position where the house is standing, which stands in for the block the window expects
     */
    public boolean open(@Nonnull final Store<EntityStore> store,
                        @Nonnull final Ref<EntityStore> player,
                        @Nonnull final Ref<EntityStore> voxel,
                        @Nonnull final Vector3d position) {

        final var playerComponent = store.getComponent(player, Player.getComponentType());
        final var uuidComponent = store.getComponent(player, UUIDComponent.getComponentType());
        if (playerComponent == null || uuidComponent == null) return false;

        final UUID uuid = uuidComponent.getUuid();
        final var windows = bench.getWindows();

        final var window = new YagaFurnaceWindow(state, bench, detached, voxel,
            (int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z),
            ROTATION, blockType);

        // One window per player, as a furnace block has, so clicking a furnace already open in front of
        // somebody does not give them a second view of it that the first will not update.
        if (windows.putIfAbsent(uuid, window) != null) return false;

        // Before the window is shown, so the fire gauge is right on the first frame rather than a tick late.
        state.updateFuelValues(windows);

        if (!playerComponent.getPageManager().setPageWithWindows(player, store, Page.Bench, true, window)) {
            windows.remove(uuid, window);
            return false;
        }

        window.registerCloseEvent(event -> windows.remove(uuid, window));
        return true;
    }

    /**
     * Whether there is nothing for {@link #tick} to do.
     *
     * <p>A cold furnace with nothing in it is the usual state of one, and a house parked in a clearing
     * should not be paying for a smelt it is not doing. What is in the output tray does not count: it sits
     * there until somebody takes it.
     */
    public boolean isIdle() {
        return !state.isActive()
            && state.getFuelTime() <= 0
            && state.getInputContainer().isEmpty()
            && state.getFuelContainer().isEmpty();
    }

    /**
     * Moves the clock on without doing anything else.
     *
     * <p>For the idle case, and the reason idling is not simply skipped: the elapsed time is measured from
     * when the furnace last ran, so a furnace that stopped counting while it stood empty would treat the
     * whole of that emptiness as time owed the moment somebody dropped an ore in it, and smelt through
     * every scrap of fuel they had in one tick.
     */
    public void idle(@Nonnull final Store<EntityStore> store) {
        state.setLastTickGameTime(store.getResource(WorldTimeResource.getResourceType()).getGameTime());
    }

    /**
     * Runs the furnace up to the current moment.
     *
     * <p>The same sequence as the engine's {@code BenchSystems.ProcessingBenchTick}, less everything it
     * does to the block it stands in: the lit appearance, the sounds that come out of the block, and the
     * interaction state that drives both. What is left is the smelting, which is all of it a house can
     * carry.
     *
     * <p>Time comes from the world clock rather than the tick, exactly as it does for a furnace block, so
     * that the moment it last ran is a thing that can be written down — which is what lets a house
     * restored tomorrow pick up where it left off.
     *
     * <p>Must run on the world thread outside of ticking, since output that will not fit is thrown on the
     * ground and a tick may not spawn entities.
     *
     * @param position where the house is standing
     */
    public void tick(@Nonnull final Store<EntityStore> store, @Nonnull final Vector3d position) {
        final ProcessingBench processing = state.getProcessingBench();
        if (processing == null) return;

        final World world = store.getExternalData().getWorld();
        final Instant now = store.getResource(WorldTimeResource.getResourceType()).getGameTime();
        final float dt = elapsedSeconds(now, world);

        try {
            smelt(store, processing, dt, position);
        } finally {
            // In a finally so that every way out of the smelt leaves the clock where the engine leaves it.
            // A path that returned without doing this would count the time it skipped twice.
            state.setLastTickGameTime(now);
        }
    }

    /**
     * How long the furnace has to make up, in seconds of burning.
     *
     * <p>Measured off the game clock and converted back, which sounds like a detour but is what makes the
     * figure meaningful across an unload: real time is not recorded anywhere in a save file, and game time
     * is.
     */
    private float elapsedSeconds(@Nullable final Instant now, @Nonnull final World world) {
        final Instant last = state.getLastTickGameTime();
        if (last == null || now == null || now.equals(last)) return 0f;

        final float gameSeconds = Math.max(0, now.toEpochMilli() - last.toEpochMilli()) / 1000f;
        return (float) (gameSeconds / WorldTimeResource.getSecondsPerTick(world));
    }

    private void smelt(@Nonnull final Store<EntityStore> store,
                       @Nonnull final ProcessingBench processing,
                       final float dt,
                       @Nonnull final Vector3d position) {

        final int x = (int) Math.floor(position.x);
        final int y = (int) Math.floor(position.y);
        final int z = (int) Math.floor(position.z);

        final var windows = bench.getWindows();
        final boolean hasFuelSlots = processing.getFuel() != null;

        state.getProcessingSlots().clear();
        state.checkForRecipeUpdate(bench);

        boolean canProcess = false;

        if (state.getRecipe() != null) {
            // Both of these stall the furnace with its ingredients intact rather than smelting into
            // nowhere: a full output tray, or an input stack too small for the recipe that matched it.
            if (!state.getOutputContainer().canAddItemStacks(
                CraftingManager.getOutputItemStacks(state.getRecipe()), false, false)) {

                state.setActive(false, bench, detached);
                return;
            }

            final var picked = state.getInputContainer().getSlotMaterialsToRemove(
                CraftingManager.getInputMaterials(state.getRecipe()), ProcessingBenchBlock.EXACT_RESOURCE_AMOUNTS, true);

            if (picked.isEmpty()) {
                state.setInputProgress(0);
                state.setActive(false, bench, detached);
                state.clearCurrentRecipe();
                return;
            }

            for (final var slot : picked) state.getProcessingSlots().addAll(slot.getPickedSlots());
            state.sendProcessingSlots(windows);
            canProcess = true;

        } else if (!hasFuelSlots) {
            // Nothing to make and no fire to keep alive. A bench like this only does anything at all while
            // a recipe is sitting in it.
            return;

        } else {
            state.sendProgress(0, windows);

            // An empty furnace goes out, unless it is the sort that burns for its own sake.
            if (!processing.shouldAllowNoInputProcessing()) {
                state.setActive(false, bench, detached);
                return;
            }
        }

        final int completions = state.advanceProcessing(dt, store, bench, detached, x, y, z, blockType, ROTATION);

        if (!canProcess && state.isActive()) {
            state.consumeFuelForDuration(dt, store, x, y, z, blockType, ROTATION);
        }

        state.getProcessingFuelSlots().clear();

        if (hasFuelSlots) {
            markBurningSlot(processing);

            if (!state.isActive() || state.getFuelTime() <= 0) {
                state.setLastConsumedFuelTotal(0);
                state.setActive(false, bench, detached);
                return;
            }

            state.updateFuelValues(windows);
        }

        if (completions > 0) {
            state.playSound(state.getBench().getCompletedSoundEventIndex(), store, blockType, ROTATION, x, y, z);
        } else if (canProcess) {
            final float recipeTime = state.getRecipeTimeSeconds(bench.getTierLevel());
            state.sendProgress(recipeTime > 0 ? state.getInputProgress() / recipeTime : 0, windows);
        }
    }

    /** Marks the fuel slot the fire is eating from, which is where the client draws its flame. */
    private void markBurningSlot(@Nonnull final ProcessingBench processing) {
        final var fuelSlots = processing.getFuel();
        if (fuelSlots == null || !state.isActive() || state.getFuelTime() <= 0) return;

        for (short slot = 0; slot < fuelSlots.length; slot++) {
            if (state.getFuelContainer().getItemStack(slot) == null) continue;
            state.getProcessingFuelSlots().add(slot);
            return;
        }
    }
}
