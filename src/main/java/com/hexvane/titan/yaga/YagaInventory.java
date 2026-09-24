package com.hexvane.titan.yaga;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.util.ArrayList;
import java.util.Arrays;

import javax.annotation.Nonnull;

/**
 * Moving a house's contents from one set of cupboards to another.
 *
 * <p>Exists for the upgrade, where a baby's one small chest becomes a baba's two large ones. Copying the
 * player's belongings is the part of that swap it would be least acceptable to get wrong.
 */
public final class YagaInventory {

    private YagaInventory() {
    }

    /** Restore smaller chests without discarding excess items. Extra containers are saved recovery storage. */
    public static SimpleItemContainer[] restore(
        ItemContainer[] saved,
        SimpleItemContainer[] chests) {
        var pending = new ArrayList<ItemStack>();
        for (int i = 0; i < saved.length; i++) {
            for (short slot = 0; slot < saved[i].getCapacity(); slot++) {
                var stack = saved[i].getItemStack(slot);
                if (ItemStack.isEmpty(stack)) continue;
                if (i < chests.length && slot < chests[i].getCapacity()
                    && ItemStack.isEmpty(chests[i].getItemStack(slot))) chests[i].setItemStackForSlot(slot, stack);
                else pending.add(stack);
            }
        }
        var overflow = new ArrayList<ItemStack>();
        for (var stack : pending) {
            for (var chest : chests) {
                stack = chest.addItemStack(stack).getRemainder();
                if (ItemStack.isEmpty(stack)) break;
            }
            if (!ItemStack.isEmpty(stack)) overflow.add(stack);
        }
        if (overflow.isEmpty()) return chests;
        var result = Arrays.copyOf(chests, chests.length + 1);
        var recovery = new SimpleItemContainer((short) overflow.size());
        for (short i = 0; i < overflow.size(); i++) recovery.setItemStackForSlot(i, overflow.get(i));
        result[chests.length] = recovery;
        return result;
    }

    /** Opening a chest makes any recovered items available as space is freed. Returns whether any remain. */
    public static boolean refillRecovered(ItemContainer[] containers, int chestCount) {
        boolean remaining = false;
        for (int i = chestCount; i < containers.length; i++) {
            for (short slot = 0; slot < containers[i].getCapacity(); slot++) {
                var stack = containers[i].getItemStack(slot);
                if (ItemStack.isEmpty(stack)) continue;
                for (int chest = 0; chest < chestCount; chest++) {
                    stack = containers[chest].addItemStack(stack).getRemainder();
                    if (ItemStack.isEmpty(stack)) break;
                }
                containers[i].setItemStackForSlot(slot, stack);
                remaining |= !ItemStack.isEmpty(stack);
            }
        }
        return remaining;
    }

    /**
     * Copies {@code from} into {@code to}, slot for slot as far as it reaches and then wherever there is
     * room.
     *
     * <p>Slot for slot first because a chest whose contents move around when the house grows would read as
     * the game rearranging the player's things. The fallback matters when the new container is smaller,
     * which the upgrade never does but a mis-authored variant could.
     *
     * @return how many stacks could not be placed, which is {@code 0} whenever the target is not smaller
     */
    public static int transfer(@Nonnull final ItemContainer from, @Nonnull final ItemContainer to) {
        int dropped = 0;
        final short capacity = from.getCapacity();

        for (short slot = 0; slot < capacity; slot++) {
            final ItemStack stack = from.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) continue;

            if (slot < to.getCapacity() && ItemStack.isEmpty(to.getItemStack(slot))) {
                to.setItemStackForSlot(slot, stack);
                continue;
            }

            final var transaction = to.addItemStack(stack);
            if (!ItemStack.isEmpty(transaction.getRemainder())) dropped++;
        }

        return dropped;
    }

    /** How many non-empty stacks {@code container} holds. For reporting what an upgrade moved. */
    public static int count(@Nonnull final ItemContainer container) {
        int stacks = 0;
        final short capacity = container.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            if (!ItemStack.isEmpty(container.getItemStack(slot))) stacks++;
        }
        return stacks;
    }

    /**
     * A cheap summary of what is in {@code containers}, so a sweep can tell whether anything was moved.
     *
     * <p>Used to decide whether a house is worth writing to disk again. Comparing summaries is the whole
     * reason this exists: without it, every sweep would have to assume the chests had changed and a parked
     * house nobody was touching would rewrite the save file every couple of seconds.
     *
     * <p>Item and quantity per slot, which is what the player can see. Durability, enchantments and the
     * rest are left out deliberately — a summary that changed on every swing of a pickaxe stored in a chest
     * would defeat the point, and the worst a missed change costs is that it saves on the next real one.
     */
    public static int fingerprint(@Nonnull final ItemContainer[] containers) {
        int hash = 1;
        for (final ItemContainer container : containers) {
            final short capacity = container.getCapacity();
            for (short slot = 0; slot < capacity; slot++) {
                final ItemStack stack = container.getItemStack(slot);
                hash = hash * 31 + (ItemStack.isEmpty(stack)
                    ? 0
                    : String.valueOf(stack.getItemId()).hashCode() * 31 + stack.getQuantity());
            }
        }
        return hash;
    }
}
