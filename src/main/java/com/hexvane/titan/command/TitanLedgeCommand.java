package com.hexvane.titan.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** {@code /titan ledge}: grab-ledge cling prototype. */
public final class TitanLedgeCommand extends AbstractCommandCollection {

    public TitanLedgeCommand() {
        super("ledge", "titan_commands.commands.titan.ledge.desc");
        addSubCommand(new TitanLedgeSpawnCommand());
        addSubCommand(new TitanLedgePlaygroundCommand());
    }
}
