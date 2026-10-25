package com.lucachinou.packsync;

import java.util.*;

/** Deltas are keyed by datapack ID; catalog iteration order is not significant. */
public final class CatalogDiff {
    private CatalogDiff() { }

    public static Protocol.Delta between(List<Protocol.Entry> before, List<Protocol.Entry> after) {
        Map<String, Protocol.Entry> old = byId(before);
        Map<String, Protocol.Entry> current = byId(after);
        List<Protocol.Entry> changed = current.values().stream()
                .filter(entry -> !entry.equals(old.get(entry.id())))
                .sorted(Comparator.comparing(Protocol.Entry::id)).toList();
        List<String> removed = old.keySet().stream().filter(id -> !current.containsKey(id)).sorted().toList();
        return new Protocol.Delta(changed, removed);
    }

    public static List<Protocol.Entry> apply(List<Protocol.Entry> before, Protocol.Delta delta) {
        Map<String, Protocol.Entry> result = byId(before);
        delta.removed().forEach(result::remove);
        byId(delta.changed()).forEach(result::put);
        if (result.size() > Protocol.MAX_PACKS) throw new IllegalArgumentException("Too many packs");
        return result.values().stream().sorted(Comparator.comparing(Protocol.Entry::id)).toList();
    }

    private static Map<String, Protocol.Entry> byId(List<Protocol.Entry> entries) {
        Map<String, Protocol.Entry> result = new HashMap<>();
        for (var entry : entries) {
            if (result.put(entry.id(), entry) != null) throw new IllegalArgumentException("Duplicate datapack ID: " + entry.id());
        }
        return result;
    }
}
