package com.modtoolkit.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Copies testdata/target-sample and gives it a real git history, whose messages cite program ids. */
final class TargetRepoFixture {
    private TargetRepoFixture() {}

    static Path create(Path repo, Path into) throws Exception {
        Path src = repo.resolve("testdata/target-sample");
        Path dst = Files.createDirectories(into.resolve("target-sample"));
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path d = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(d); else Files.copy(p, d);
            }
        }
        git(dst, "init", "-q", "-b", "main");
        git(dst, "add", "README.md");
        commit(dst, "chore: start");
        git(dst, "add", "src/main/java/com/acme/card/batch", "src/main/java/com/acme/card/entity", "src/main/java/com/acme/card/repo");
        commit(dst, "Port CBACT04C interest calculation to Spring Batch");
        git(dst, "add", ".");
        commit(dst, "Account view screen");
        return dst;
    }

    private static void commit(Path d, String msg) throws Exception {
        git(d, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", msg);
    }

    private static void git(Path d, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", d.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(cmd + ": " + out);
    }
}
