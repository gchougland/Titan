package com.hexvane.titan.crypt;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import org.bson.BsonDocument;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptVictoryPersistenceTest {
    @Test void coffinIsOnlyAnEncounterTriggerWithoutInventoryOrWindowStates() throws Exception {
        assertEquals(SimpleBlockInteraction.class, CryptCoffinInteraction.class.getSuperclass());
        try (var input = getClass().getResourceAsStream("/Server/Item/Items/Titan/Titan_Crypt_Coffin.json")) {
            assertNotNull(input);
            var asset = BsonDocument.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var block = asset.getDocument("BlockType");
            assertFalse(block.containsKey("BlockEntity"));
            var states = block.getDocument("State").getDocument("Definitions");
            assertFalse(states.containsKey("OpenWindow"));
            assertFalse(states.containsKey("CloseWindow"));
        }
    }

    @Test void pendingFinalBlowSurvivesCodecReloadButTransientCombatDoesNot() {
        var site = new CryptSiteComponent();
        site.setVictoryPending(true);
        site.setActive(true);
        site.setPending(true);
        var info = ExtraInfo.THREAD_LOCAL.get();
        var saved = CryptSiteComponent.CODEC.encode(site, info);
        var loaded = CryptSiteComponent.CODEC.decode(BsonDocument.parse(saved.toJson()), info);
        assertTrue(loaded.isVictoryPending());
        assertFalse(loaded.isCleared());
        assertFalse(loaded.isRewardDeposited());
        assertFalse(loaded.isActive());
        assertFalse(loaded.isPending());
        assertTrue(((CryptSiteComponent) site.clone()).isVictoryPending());
        assertFalse(CryptSiteComponent.CODEC.decode(BsonDocument.parse("{}"), info).isVictoryPending());
    }

    @Test void worldJournalRetainsUnloadedMarkerVictoryAndSingleDeliveryAcrossReload() {
        var coffin = new Vector3i(-86,105,0);
        var other = new Vector3i(-86,106,0);
        var memory = new CryptVictoryMemory();
        assertTrue(memory.recordVictory(coffin));
        assertFalse(memory.recordVictory(coffin));
        var info = ExtraInfo.THREAD_LOCAL.get();
        var reloaded = CryptVictoryMemory.CODEC.decode(CryptVictoryMemory.CODEC.encode(memory,info),info);
        assertTrue(reloaded.hasVictory(coffin));
        assertFalse(reloaded.hasVictory(other));
        assertFalse(reloaded.hasDeposit(coffin));
        assertTrue(reloaded.recordDeposit(coffin));
        assertFalse(reloaded.recordDeposit(coffin));
        var afterClaim = CryptVictoryMemory.CODEC.decode(CryptVictoryMemory.CODEC.encode(reloaded,info),info);
        assertTrue(afterClaim.hasVictory(coffin));
        assertTrue(afterClaim.hasDeposit(coffin));
        assertTrue(afterClaim.clone().hasDeposit(coffin));
    }

    @Test void aDeliveryOnlyJournalStillPermanentlyRetiresThatDungeon() {
        var info = ExtraInfo.THREAD_LOCAL.get();
        var memory = CryptVictoryMemory.CODEC.decode(BsonDocument.parse(
            "{\"Deposited\":[\"-86:105:0\"]}"), info);
        var coffin = new Vector3i(-86, 105, 0);
        assertTrue(memory.hasVictory(coffin));
        assertTrue(memory.hasDeposit(coffin));
        assertFalse(memory.hasVictory(new Vector3i(426, 105, 0)));
        assertFalse(new CryptVictoryMemory().hasVictory(coffin), "Another world has its own journal");
        assertTrue(memory.recordDeposit(coffin), "Restore the missing victory index when saving again");
        assertFalse(memory.recordDeposit(coffin));
        var loaded = CryptVictoryMemory.CODEC.decode(CryptVictoryMemory.CODEC.encode(memory, info), info);
        assertTrue(loaded.hasVictory(coffin));
        assertTrue(loaded.clone().hasVictory(coffin));
    }

    @Test void everyPersistedVictoryReceiptPreventsReawakeningButAnUnfinishedFightDoesNot() {
        var info = ExtraInfo.THREAD_LOCAL.get();
        for (String receipt : new String[]{"Cleared", "VictoryPending", "RewardDeposited"}) {
            var saved = BsonDocument.parse("{\"" + receipt + "\":true}");
            var site = CryptSiteComponent.CODEC.decode(saved, info);
            assertTrue(site.isDefeated(), receipt);
            assertTrue(((CryptSiteComponent) site.clone()).isDefeated(), receipt);
            assertTrue(CryptSiteComponent.CODEC.decode(CryptSiteComponent.CODEC.encode(site, info), info).isDefeated(), receipt);
        }
        var unfinished = new CryptSiteComponent();
        unfinished.setActive(true);
        unfinished.setPending(true);
        assertFalse(unfinished.isDefeated());
        assertFalse(CryptSiteComponent.CODEC.decode(CryptSiteComponent.CODEC.encode(unfinished, info), info).isDefeated());
    }
}
