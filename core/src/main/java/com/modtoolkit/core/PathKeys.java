package com.modtoolkit.core;

import java.nio.file.Path;
import java.util.Set;

/**
 * Paths relative to a root, as stored in the database, cited and compared: name elements joined with '/'
 * on every platform. Production code uses this instead of {@code Path.relativize(...).toString()}, which
 * gives backslashes on Windows.
 */
public final class PathKeys {
    private PathKeys() {}

    /** The path of {@code file} relative to {@code root}, its name elements joined with '/'. */
    public static String key(Path root, Path file) {
        StringBuilder b = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (!b.isEmpty()) b.append('/');
            b.append(part);
        }
        return b.toString();
    }

    /** True when any name element of {@code file} relative to {@code root} is one of {@code dirNames}. */
    public static boolean under(Path root, Path file, Set<String> dirNames) {
        for (Path part : root.relativize(file)) if (dirNames.contains(part.toString())) return true;
        return false;
    }
}
