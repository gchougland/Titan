package com.hexvane.titan.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

/** Root of {@code /titan}. The subcommands spawn and remove bosses, so the collection sits behind world edit. */
public final class TitanCommand extends AbstractCommandCollection {
    public TitanCommand() {
        super("titan", "titan_commands.commands.titan.root.desc");
        setPermissionGroups(HytalePermissionsProvider.GROUP_WORLD_EDITOR);
        addAliases("ti");

        addSubCommand(new TitanSpawnCommand());
        addSubCommand(new TitanKillCommand());
        addSubCommand(new TitanListCommand());
        addSubCommand(new TitanSitesCommand());
        addSubCommand(new TitanAnimCommand());
        addSubCommand(new TitanDanceCommand());
        addSubCommand(new TitanDebugCommand());
        addSubCommand(new TitanPerfCommand());
        addSubCommand(new TitanPreviewCommand());
    }
}
