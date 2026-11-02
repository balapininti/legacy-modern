package com.modtoolkit.parsers.cobol;

import java.util.List;
import java.util.Map;

/** Source with COPY resolved, one origin per line, so each fact can name the member and line it came from. */
public record ExpandedSource(
        String member, boolean free, List<String> lines, List<Loc> origins, List<CopyUse> copies, List<String> missing, List<Duplicate> duplicates) {
    public record CopyUse(String name, Loc at, Map<String, String> replacing, boolean system, boolean found, String path) {}
    public record Duplicate(String name, List<String> paths) {}

    public Loc origin(int expandedLine) {
        int i = Math.max(1, Math.min(expandedLine, origins.size()));
        return origins.isEmpty() ? new Loc(member, expandedLine) : origins.get(i - 1);
    }
}
