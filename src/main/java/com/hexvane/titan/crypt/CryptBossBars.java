package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.packets.interface_.UpdateBossBar;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Own only the Crypt bars sent to this connection; the holder survives player world transfers. */
final class CryptBossBars {
    private static final Map<PlayerRef, Set<Integer>> SHOWN = new WeakHashMap<>();
    private CryptBossBars() { }

    static void show(PlayerRef player, int entityId, FormattedMessage title) {
        synchronized (SHOWN) {
            player.getPacketHandler().writeNoCache(new UpdateBossBar(entityId, title, false));
            SHOWN.computeIfAbsent(player, ignored -> new HashSet<>()).add(entityId);
        }
    }

    static void hide(PlayerRef player, int entityId) {
        synchronized (SHOWN) {
            var owned = SHOWN.get(player);
            if (owned == null || !owned.remove(entityId)) return;
            if (owned.isEmpty()) SHOWN.remove(player);
            player.getPacketHandler().writeNoCache(new UpdateBossBar(entityId, null, true));
        }
    }

    private static void removePlayer(Holder<EntityStore> holder) {
        var player = holder.getComponent(PlayerRef.getComponentType());
        if (player == null) return;
        synchronized (SHOWN) {
            var owned = SHOWN.remove(player);
            if (owned == null) return;
            for (int entityId : owned) player.getPacketHandler().writeNoCache(new UpdateBossBar(entityId, null, true));
        }
    }

    static final class PlayerRemoval extends HolderSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public void onEntityAdd(Holder<EntityStore> holder, AddReason reason, Store<EntityStore> store) { }
        @Override public void onEntityRemoved(Holder<EntityStore> holder, RemoveReason reason, Store<EntityStore> store) {
            removePlayer(holder);
        }
    }
}
