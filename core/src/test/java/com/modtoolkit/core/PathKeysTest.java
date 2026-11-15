package com.modtoolkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PathKeysTest {
    @Test
    void windowsPathsAreKeyedWithForwardSlashes() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.windows())) {
            Path root = fs.getPath("C:\\src");
            Path file = fs.getPath("C:\\src\\app\\cbl\\X.cbl");
            assertThat(root.relativize(file).toString()).isEqualTo("app\\cbl\\X.cbl");
            assertThat(PathKeys.key(root, file)).isEqualTo("app/cbl/X.cbl");
        }
    }

    @Test
    void unixPathsAreKeyedTheSameWay() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            assertThat(PathKeys.key(fs.getPath("/src"), fs.getPath("/src/app/cbl/X.cbl"))).isEqualTo("app/cbl/X.cbl");
            assertThat(PathKeys.key(fs.getPath("/src"), fs.getPath("/src/X.cbl"))).isEqualTo("X.cbl");
        }
        // The default file system too, whatever its separator.
        Path root = Path.of("src").toAbsolutePath();
        Path file = root.resolve("a").resolve("b").resolve("C.java");
        assertThat(PathKeys.key(root, file)).isEqualTo("a/b/C.java");
    }

    @Test
    void underMatchesAnyRelativeElement() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.windows())) {
            Path root = fs.getPath("C:\\repo");
            Set<String> skip = Set.of("node_modules", "target");
            assertThat(PathKeys.under(root, fs.getPath("C:\\repo\\ui\\node_modules\\x\\a.ts"), skip)).isTrue();
            assertThat(PathKeys.under(root, fs.getPath("C:\\repo\\target\\A.java"), skip)).isTrue();
            assertThat(PathKeys.under(root, fs.getPath("C:\\repo\\src\\main\\A.java"), skip)).isFalse();
        }
        // Elements of the root itself do not count.
        assertThat(PathKeys.under(Path.of("/w/target/repo"), Path.of("/w/target/repo/src/A.java"), Set.of("target"))).isFalse();
    }
}
