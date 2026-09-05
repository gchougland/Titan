package com.hexvane.titan.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /titan yaga}: the Baba Yaga house, from the outside.
 *
 * <p>Its own collection rather than more subcommands under {@code /titan}, because a house is not really
 * one of the things the rest of that tree deals with. {@code /titan spawn} will build one, but nothing
 * there can grow it, throw away what it remembers, or hand out the wand it is directed with.
 *
 * <p>All of these exist so the house can be exercised without waiting for an egg to be found in a redwood
 * forest, which is the only way one appears otherwise. Growing it is the exception and is meant to stay:
 * an item that does the same thing is planned, and both go through {@code YagaUpgrade}.
 */
public final class TitanYagaCommand extends AbstractCommandCollection {

    public TitanYagaCommand() {
        super("yaga", "titan_yaga.commands.titan.yaga.desc");

        addSubCommand(new TitanYagaSpawnCommand());
        addSubCommand(new TitanYagaUpgradeCommand());
        addSubCommand(new TitanYagaForgetCommand());
        addSubCommand(new TitanYagaWandCommand());
    }
}
