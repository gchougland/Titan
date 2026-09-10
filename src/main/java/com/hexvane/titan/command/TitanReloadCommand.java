package com.hexvane.titan.command;

import com.hexvane.titan.TitanPlugin;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Works for administrators in game and from the server console. */
public final class TitanReloadCommand extends AbstractAsyncCommand {
    public TitanReloadCommand() {
        super("reload", "Reload Titan's server configuration.");
        setPermissionGroups(HytalePermissionsProvider.GROUP_WORLD_EDITOR);
    }

    @Override @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
        var plugin = TitanPlugin.get();
        if (plugin == null) {
            context.sendMessage(Message.raw("Titan is not loaded."));
            return CompletableFuture.completedFuture(null);
        }
        return plugin.reloadConfig().handle((ignored, error) -> {
            if (error == null) {
                context.sendMessage(Message.raw("Titan config reloaded. Health and level scaling apply to new encounters; combat settings apply on the next attack."));
            } else {
                plugin.getLogger().atWarning().withCause(error).log("Titan config reload failed");
                context.sendMessage(Message.raw("Could not reload Titan config. Previous settings remain active; check config.json and the server log."));
            }
            return null;
        });
    }
}
