package com.modtoolkit.discovery;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record DiscoverOptions(Path root, String encoding, String format, List<String> include, List<String> exclude, int threads, Duration memberTimeout) {
    public static DiscoverOptions of(Path root) {
        return new DiscoverOptions(root, "auto", "auto", List.of("**/*"), List.of(".git/**", "**/.git/**"), Runtime.getRuntime().availableProcessors(), Duration.ofMinutes(5));
    }

    public DiscoverOptions withThreads(int n) {
        return new DiscoverOptions(root, encoding, format, include, exclude, n, memberTimeout);
    }

    public DiscoverOptions withTimeout(Duration t) {
        return new DiscoverOptions(root, encoding, format, include, exclude, threads, t);
    }

    public DiscoverOptions withExclude(List<String> ex) {
        return new DiscoverOptions(root, encoding, format, include, ex, threads, memberTimeout);
    }
}
