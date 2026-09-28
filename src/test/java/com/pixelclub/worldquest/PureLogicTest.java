package com.pixelclub.worldquest;

import com.pixelclub.worldquest.config.WqConfig;
import com.pixelclub.worldquest.offer.Confirmations;
import com.pixelclub.worldquest.state.StateStore;
import com.pixelclub.worldquest.state.WqState;
import com.pixelclub.worldquest.util.AtomicFiles;
import com.pixelclub.worldquest.util.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PureLogicTest {

    private static long filled(String bar) {
        // "&a■■■&8■■" → count cells between the first colour code and the second
        int second = bar.indexOf('&', 2);
        return bar.substring(2, second).length();
    }

    @Test
    void barNeverShowsFullBeforeTheGoal() {
        assertEquals(29, filled(Text.bar(99, 100, 30, "&a", "&8", '■')));
        assertEquals(29, filled(Text.bar(99_999, 100_000, 30, "&a", "&8", '■')));
        assertEquals(30, filled(Text.bar(100, 100, 30, "&a", "&8", '■')));
        assertEquals(30, filled(Text.bar(150, 100, 30, "&a", "&8", '■')));
    }

    @Test
    void barShowsAtLeastOneCellForAnyProgress() {
        assertEquals(1, filled(Text.bar(1, 1_000_000, 30, "&a", "&8", '■')));
        assertEquals(0, filled(Text.bar(0, 100, 30, "&a", "&8", '■')));
        assertEquals(17, filled(Text.bar(57, 100, 30, "&a", "&8", '■')));
    }

    @Test
    void bahtReadsInLanNeverRoundingUp() {
        assertEquals("999,999", Text.baht(999_999));
        assertEquals("1 ล้าน", Text.baht(1_000_000));
        assertEquals("1.9 ล้าน", Text.baht(1_999_999));
        assertEquals("120.5 ล้าน", Text.baht(120_550_000));
        assertEquals("2,293.2 ล้าน".replace(",", ""), Text.baht(2_293_200_000L));
    }

    @Test
    void percentIsFlooredAndCapped() {
        assertEquals(57, Text.percent(57, 100));
        assertEquals(99, Text.percent(49_999, 50_000));
        assertEquals(100, Text.percent(5, 0));
    }

    @Test
    void confirmationIsBoundToItsPlayerAndUsedOnce() {
        Confirmations c = new Confirmations();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Confirmations.Pending p = c.put(a, Confirmations.Kind.DONATE, "q", 10_000, 1, null, null, 30_000);
        assertNull(c.take(b, p.nonce), "another player's click must not confirm it");
        assertNotNull(c.take(a, p.nonce));
        assertNull(c.take(a, p.nonce), "a nonce is single-use");
    }

    @Test
    void aNewPromptReplacesTheOldOne() {
        Confirmations c = new Confirmations();
        UUID a = UUID.randomUUID();
        Confirmations.Pending first = c.put(a, Confirmations.Kind.DONATE, "q", 10_000, 1, null, null, 30_000);
        Confirmations.Pending second = c.put(a, Confirmations.Kind.DONATE, "q", 20_000, 2, null, null, 30_000);
        assertNull(c.take(a, first.nonce), "the old prompt's button must stop working");
        assertEquals(20_000, c.take(a, second.nonce).amount);
    }

    @Test
    void expiredConfirmationIsRefused() {
        Confirmations c = new Confirmations();
        UUID a = UUID.randomUUID();
        Confirmations.Pending p = c.put(a, Confirmations.Kind.DONATE, "q", 10_000, 1, null, null, -1);
        assertNull(c.take(a, p.nonce));
    }

    @Test
    void missingConfigIsWrittenWithDefaults(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.json");
        WqConfig c = WqConfig.load(f);
        assertTrue(Files.exists(f));
        assertEquals(1, c.quests.size());
        assertEquals("endless_unlock", c.quests.get(0).id);
        assertEquals(100, c.quests.get(0).conquerors.target);
        assertEquals("manual", c.quests.get(0).openMode);
    }

    @Test
    void partialConfigKeepsDefaultsForMissingKeys(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.json");
        Files.writeString(f, "{\"moneyPerPoint\": 5000, \"quests\": [{\"id\": \"x\", \"fund\": {\"targetPoints\": 7}}]}",
                StandardCharsets.UTF_8);
        WqConfig c = WqConfig.load(f);
        assertEquals(5000, c.moneyPerPoint);
        WqConfig.QuestDef q = c.quest("x");
        assertEquals(7, q.fund.targetPoints);
        assertTrue(q.fund.acceptMoney, "a key the file lacks keeps its default");
        assertNotNull(q.conquerors);
        assertNotNull(c.messages.prefix);
    }

    @Test
    void brokenConfigThrowsInsteadOfResetting(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.json");
        Files.writeString(f, "{ this is not json", StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> WqConfig.load(f));
        assertEquals("{ this is not json", Files.readString(f), "the bad file is left for the admin to fix");
    }

    @Test
    void stateRoundTrips(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("state.json");
        StateStore s = new StateStore(f, LoggerFactory.getLogger("t"));
        s.load();
        WqState.QuestState q = s.state().quest("endless_unlock");
        q.fundPoints = 42;
        q.player("u1", "Alice").fundPoints = 42;
        s.save();
        s.close();
        StateStore again = new StateStore(f, LoggerFactory.getLogger("t"));
        again.load();
        assertFalse(again.broken());
        assertEquals(42, again.state().quest("endless_unlock").fundPoints);
        assertEquals("Alice", again.state().quest("endless_unlock").players.get("u1").name);
    }

    @Test
    void corruptStateFreezesAndIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("state.json");
        Files.writeString(f, "{\"quests\": {\"endless_unlock\": [1,2", StandardCharsets.UTF_8);
        StateStore s = new StateStore(f, LoggerFactory.getLogger("t"));
        s.load();
        assertTrue(s.broken());
        s.save();
        s.close();
        assertEquals("{\"quests\": {\"endless_unlock\": [1,2", Files.readString(f));
    }

    @Test
    void atomicWriteLeavesNoTempFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.json");
        AtomicFiles.writeString(f, "one");
        AtomicFiles.writeString(f, "two");
        assertEquals("two", Files.readString(f));
        assertFalse(Files.exists(dir.resolve("a.json.tmp")));
    }
}
