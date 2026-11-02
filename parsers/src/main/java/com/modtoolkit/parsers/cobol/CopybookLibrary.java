package com.modtoolkit.parsers.cobol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Copybooks by name. Names match case-insensitively; duplicates resolve to the shortest then first path. */
public final class CopybookLibrary {
    public record Entry(String name, String path, List<String> lines, boolean classified) {}

    /** Supplied by the compiler or subsystem, so their absence from an export is not a defect. */
    public static final Set<String> SYSTEM = Set.of("SQLCA", "SQLDA", "DFHAID", "DFHBMSCA", "DFHEIBLK", "DFHCOMMAREA", "DFHATTR", "DFHMSRCA", "DFHTRMPA", "CMQV", "CMQTML", "CMQODV", "CMQMDV", "CMQPMOV", "CMQGMOV", "CMQCD");

    private final Map<String, List<Entry>> byName = new HashMap<>();

    public void add(String name, String path, List<String> lines) {
        add(name, path, lines, true);
    }

    /** @param classified false for a member that only might be a copybook; a real one always wins the name */
    public void add(String name, String path, List<String> lines, boolean classified) {
        byName.computeIfAbsent(name.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(new Entry(name, path, lines, classified));
    }

    public Optional<Entry> resolve(String name) {
        List<Entry> l = byName.get(name.toUpperCase(Locale.ROOT));
        if (l == null) return Optional.empty();
        return l.stream().min(Comparator.<Entry, Boolean>comparing(e -> !e.classified()).thenComparingInt(e -> e.path().length()).thenComparing(Entry::path));
    }

    public List<String> duplicates(String name) {
        List<Entry> l = byName.getOrDefault(name.toUpperCase(Locale.ROOT), List.of());
        return l.size() < 2 ? List.of() : l.stream().map(Entry::path).sorted().toList();
    }
}
