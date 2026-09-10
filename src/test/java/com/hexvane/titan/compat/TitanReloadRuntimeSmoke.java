package com.hexvane.titan.compat;

import com.hexvane.titan.TitanPlugin;
import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only edits the isolated smoke server's config, restoring the exact bytes afterwards. */
public final class TitanReloadRuntimeSmoke {
    public static void run() throws Exception {
        var command = CommandManager.get().getCommandRegistration().get("titan");
        if (command == null || command.getSubCommand("reload") == null) throw new AssertionError("Reload command not registered");
        var plugin = TitanPlugin.get();
        var path = plugin.getDataDirectory().resolve("config.json").toAbsolutePath().normalize();
        var allowed = Path.of(".").toAbsolutePath().normalize();
        if (!allowed.getFileName().toString().equals("crypt-smoke") || !path.startsWith(allowed))
            throw new AssertionError("Refusing to edit a non-smoke config: " + path);
        byte[] original = Files.readAllBytes(path);
        try {
            Files.writeString(path, "{\"AttackDamageMultiplier\":2.75,\"MinionsPerExtraPlayer\":0.75}");
            plugin.reloadConfig().join();
            if (TitanConfig.get().getAttackDamageMultiplier() != 2.75f || TitanConfig.get().getMinionsPerExtraPlayer() != .75)
                throw new AssertionError("Reload did not publish updated settings");
            var active = TitanConfig.get();
            Files.writeString(path, "{ broken json");
            boolean failed = false;
            try { plugin.reloadConfig().join(); } catch (java.util.concurrent.CompletionException expected) { failed = true; }
            if (!failed || TitanConfig.get() != active) throw new AssertionError("Invalid reload replaced active settings");
            Files.writeString(path, "{\"AttackDamageMultiplier\":1.25}");
            plugin.reloadConfig().join();
            if (TitanConfig.get().getAttackDamageMultiplier() != 1.25f) throw new AssertionError("Cannot retry after invalid config");
            System.out.println("[Titan reload smoke] registered command / successful reload / failure preserves active config / retry PASS");
        } finally {
            Files.write(path, original); plugin.reloadConfig().join();
        }
    }
}
