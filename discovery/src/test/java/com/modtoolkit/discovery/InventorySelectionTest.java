package com.modtoolkit.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Include and exclude globs must select the same files whatever the platform's separator. */
class InventorySelectionTest {
    private static void check(FileSystem fs, String root, String sep) {
        Path r = fs.getPath(root);
        DiscoverOptions d = DiscoverOptions.of(r);
        Predicate<Path> defaults = InventoryScanner.selector(r, d.include(), d.exclude());
        assertThat(defaults.test(r.resolve("X.cbl"))).as("root-level file").isTrue();
        assertThat(defaults.test(r.resolve("app" + sep + "cbl" + sep + "X.cbl"))).as("nested file").isTrue();
        assertThat(defaults.test(r.resolve(".git" + sep + "config"))).as("root .git").isFalse();
        assertThat(defaults.test(r.resolve("sub" + sep + ".git" + sep + "objects" + sep + "ab"))).as("nested .git").isFalse();

        Predicate<Path> lst = InventoryScanner.selector(r, d.include(), List.of("**/*.lst"));
        assertThat(lst.test(r.resolve("app" + sep + "out" + sep + "X.lst"))).as("nested .lst excluded").isFalse();
        assertThat(lst.test(r.resolve("app" + sep + "cbl" + sep + "X.cbl"))).isTrue();

        Predicate<Path> onlyCbl = InventoryScanner.selector(r, List.of("**/*.cbl"), List.of());
        assertThat(onlyCbl.test(r.resolve("X.cbl"))).as("root-level include").isTrue();
        assertThat(onlyCbl.test(r.resolve("app" + sep + "cbl" + sep + "X.cbl"))).isTrue();
        assertThat(onlyCbl.test(r.resolve("app" + sep + "jcl" + sep + "X.jcl"))).isFalse();
    }

    @Test
    void windowsPaths() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.windows())) {
            check(fs, "C:\\src", "\\");
        }
    }

    @Test
    void unixPaths() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            check(fs, "/src", "/");
        }
    }

    @Test
    void defaultFileSystem() {
        check(java.nio.file.FileSystems.getDefault(), System.getProperty("java.io.tmpdir"), java.io.File.separator);
    }
}
