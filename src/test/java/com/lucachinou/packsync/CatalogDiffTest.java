package com.lucachinou.packsync;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CatalogDiffTest {
    private static Protocol.Entry entry(String id, String hash, Protocol.Priority priority) {
        return new Protocol.Entry(id,123,hash.repeat(64),priority);
    }

    @Test void unchangedAndReorderedCatalogsProduceNoPacket() {
        var a = entry("a","a",Protocol.Priority.LOW);
        var b = entry("b","b",Protocol.Priority.HIGH);
        assertTrue(CatalogDiff.between(List.of(a,b),List.of(a,b)).isEmpty());
        assertTrue(CatalogDiff.between(List.of(a,b),List.of(b,a)).isEmpty());
        assertTrue(CatalogDiff.between(List.of(),List.of()).isEmpty());
    }

    @Test void includesOnlyAddedModifiedAndRemovedEntries() {
        var unchanged = entry("unchanged","a",Protocol.Priority.LOW);
        var removed = entry("removed","b",Protocol.Priority.LOW);
        var old = entry("changed","c",Protocol.Priority.HIGH);
        var updated = entry("changed","d",Protocol.Priority.HIGH);
        var added = entry("added","e",Protocol.Priority.CRITICAL);
        var delta = CatalogDiff.between(List.of(unchanged,removed,old),List.of(unchanged,updated,added));
        assertEquals(List.of(added,updated),delta.changed());
        assertEquals(List.of("removed"),delta.removed());
        assertTrue(CatalogDiff.between(List.of(added,updated,unchanged),CatalogDiff.apply(List.of(unchanged,removed,old),delta)).isEmpty());
    }

    @Test void detectsPriorityAndSizeChangesWithoutHashChanges() {
        var old = entry("pack","a",Protocol.Priority.LOW);
        var priority = entry("pack","a",Protocol.Priority.CRITICAL);
        assertEquals(List.of(priority),CatalogDiff.between(List.of(old),List.of(priority)).changed());
        var size = new Protocol.Entry(old.id(),456,old.sha256(),old.priority());
        assertFalse(CatalogDiff.between(List.of(old),List.of(size)).isEmpty());
    }

    @Test void removesAllPacks() {
        var old = List.of(entry("a","a",Protocol.Priority.LOW));
        var delta = CatalogDiff.between(old,List.of());
        assertEquals(List.of("a"),delta.removed());
        assertEquals(List.of(),CatalogDiff.apply(old,delta));
    }

    @Test void successiveUpdatesCanBeCoalescedDuringDownload() {
        var a = entry("a","a",Protocol.Priority.LOW);
        var b = entry("a","b",Protocol.Priority.HIGH);
        var c = entry("other","c",Protocol.Priority.CRITICAL);
        var first = CatalogDiff.between(List.of(a),List.of(b));
        var second = CatalogDiff.between(List.of(b),List.of(c));
        assertEquals(List.of(c),CatalogDiff.apply(CatalogDiff.apply(List.of(a),first),second));
    }

    @Test void rejectsDuplicateDatapackIds() {
        var a = entry("a","a",Protocol.Priority.LOW);
        assertThrows(IllegalArgumentException.class,() -> CatalogDiff.between(List.of(),List.of(a,a)));
    }
}
