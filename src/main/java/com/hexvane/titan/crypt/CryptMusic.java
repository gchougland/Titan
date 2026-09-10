package com.hexvane.titan.crypt;

import com.hexvane.titan.combat.TitanBattleMusic;
import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Uses the same forced music channel and cleanup rules as the other titan fights. */
final class CryptMusic {
    static final String TRACK = "Track_Crypt_Keeper_Battle";
    private static final String FALLBACK = "Track_Titan_Battle";

    private CryptMusic() { }

    static void apply(ComponentAccessor<EntityStore> access, Ref<EntityStore> player) {
        if (!TitanConfig.get().isBattleMusicEnabled()) { stop(access, player); return; }
        int index = resolve();
        if (index > TitanBattleMusic.NONE) TitanBattleMusic.apply(access, player, index);
    }

    static void stop(ComponentAccessor<EntityStore> access, Ref<EntityStore> player) {
        int index = resolve();
        if (index > TitanBattleMusic.NONE) TitanBattleMusic.clear(access, player, index);
    }

    static void resetOnRemoval(Holder<EntityStore> holder) {
        // Audio is optional and may not have completed setup when our system registers.
        final ForcedMusicTracker tracker;
        try {
            var type = ForcedMusicTracker.getComponentType();
            tracker = type == null ? null : holder.getComponent(type);
        } catch (RuntimeException | LinkageError unavailable) {
            return;
        }
        if (tracker == null) return;
        int index = resolve();
        if (index <= TitanBattleMusic.NONE) return;
        int desired = tracker.getCurrentContainerIndex();
        // Also handle leaving just after stop(), before Audio's tick has sent the stop packet.
        if (desired != index && (desired != TitanBattleMusic.NONE || tracker.getLastSentContainerIndex() != index)) return;
        tracker.setCurrentContainerIndex(TitanBattleMusic.NONE);
        tracker.setLastSentContainerIndex(TitanBattleMusic.NONE);
    }

    static final class PlayerRemoval extends HolderSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public void onEntityAdd(Holder<EntityStore> holder, AddReason reason, Store<EntityStore> store) { }
        @Override public void onEntityRemoved(Holder<EntityStore> holder, RemoveReason reason, Store<EntityStore> store) {
            // The engine sends container 0 on removal without resetting its tracker.
            // It sends 0 regardless of these fields, so either HolderSystem order is safe.
            // Reset both before this same holder moves to another world: re-entering a
            // crypt must produce a fresh track != lastSent comparison and music packet.
            resetOnRemoval(holder);
        }
    }

    private static int resolve() {
        int index = MusicContainer.getAssetMap().getIndex(TRACK);
        return index > TitanBattleMusic.NONE ? index : MusicContainer.getAssetMap().getIndex(FALLBACK);
    }
}
