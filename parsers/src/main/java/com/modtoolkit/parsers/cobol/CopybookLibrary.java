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
    public record Entry(String name, String path, List<String> lines) {}

    /** Supplied by the compiler or subsystem, so their absence from an export is not a defect. */
    public static final Set<String> SYSTEM = Set.of("SQLCA", "SQLDA", "DFHAID", "DFHBMSCA", "DFHEIBLK", "DFHCOMMAREA", "DFHATTR", "DFHMSRCA", "DFHTRMPA", "CMQV", "CMQTML", "CMQODV", "CMQMDV", "CMQPMOV", "CMQGMOV", "CMQCD");

    private final Map<String, List<Entry>> byName = new HashMap<>();

    public void add(String name, String path, List<String> lines) {
        byName.computeIfAbsent(name.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(new Entry(name, path, lines));
    }

    public Optional<Entry> resolve(String name) {
        List<Entry> l = byName.get(name.toUpperCase(Locale.ROOT));
        if (l == null) return Optional.empty();
        return l.stream().min(Comparator.<Entry>comparingInt(e -> e.path().length()).thenComparing(Entry::path));
    }

    public List<String> duplicates(String name) {
        List<Entry> l = byName.getOrDefault(name.toUpperCase(Locale.ROOT), List.of());
        return l.size() < 2 ? List.of() : l.stream().map(Entry::path).sorted().toList();
    }
}
