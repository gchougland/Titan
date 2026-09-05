package com.hexvane.titan.command;

import com.hexvane.titan.yaga.YagaWand;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * {@code /titan yaga wand}: puts a wand in the player's pack.
 *
 * <p>{@code /give} would do the same thing, and this exists next to it because the wand is not a reward for
 * anything yet: a player who hatches an egg has a house and no way to tell it where to go, and the whole of
 * the house's controls are behind this one item.
 */
public final class TitanYagaWandCommand extends AbstractPlayerCommand {

    public TitanYagaWandCommand() {
        super("wand", "titan_yaga.commands.titan.yaga.wand.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final var transaction = Player.giveItem(new ItemStack(YagaWand.ITEM, 1), ref, store);
        final var remainder = transaction.getRemainder();

        context.sendMessage(Message.translation(remainder == null || remainder.isEmpty()
            ? "titan_yaga.commands.titan.yaga.wand.success"
            : "titan_yaga.commands.titan.yaga.wand.full"));
    }
}
