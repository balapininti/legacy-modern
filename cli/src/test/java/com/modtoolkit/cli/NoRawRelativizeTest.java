package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Stored paths must use '/', so production code goes through PathKeys rather than Path.relativize. */
class NoRawRelativizeTest {
    @Test
    void onlyPathKeysCallsRelativize() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("mvnw"))) root = root.getParent();
        assertThat(root).as("repository root containing mvnw").isNotNull();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> modules = Files.list(root)) {
            for (Path m : modules.toList()) {
                Path src = m.resolve("src/main/java");
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> s = Files.walk(src)) {
                    for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                        scanned++;
                        if (f.getFileName().toString().equals("PathKeys.java")) continue;
                        if (Files.readString(f).contains("relativize(")) offenders.add(root.relativize(f).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertThat(scanned).as("java files scanned").isGreaterThan(50);
        assertThat(offenders).as("use PathKeys.key/under instead of relativize(").isEmpty();
    }
}
