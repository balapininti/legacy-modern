package com.modtoolkit.cli.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileTest {
    @TempDir Path dir;

    @Test
    void valuesAreTakenRawAfterTheFirstEquals() {
        Map<String, String> m = EnvFile.parse(List.of(
                "﻿# a comment",
                "",
                "   # indented comment",
                "A=plain",
                "B=has=equals==",
                "C=with spaces and %PATH% and \"quotes\" and 'single'",
                "D=",
                "E=trailing space ",
                "F=crlf\r",
                " G =x",
                "A=second wins"));
        assertThat(m).containsExactly(
                Map.entry("A", "second wins"), Map.entry("B", "has=equals=="),
                Map.entry("C", "with spaces and %PATH% and \"quotes\" and 'single'"), Map.entry("D", ""),
                Map.entry("E", "trailing space "), Map.entry("F", "crlf"), Map.entry("G", "x"));
    }

    @Test
    void aLineWithoutAKeyIsAnErrorNamingTheLine() {
        assertThatThrownBy(() -> EnvFile.parse(List.of("A=1", "not a setting"))).hasMessageContaining("line 2");
        assertThatThrownBy(() -> EnvFile.parse(List.of("1BAD=x"))).hasMessageContaining("line 1");
        assertThatThrownBy(() -> EnvFile.parse(List.of("=x"))).hasMessageContaining("line 1");
    }

    @Test
    void writeThenReadRoundTripsAndIsOwnerOnly() throws Exception {
        Path f = dir.resolve("config dir").resolve("toolkit.env");
        Map<String, String> v = new LinkedHashMap<>();
        v.put("TOOLKIT_DB_PASSWORD", "p=a ss%word\"'");
        v.put("TOOLKIT_HTTPS_PORT", "8443");
        EnvFile.write(f, v);
        assertThat(EnvFile.read(f)).isEqualTo(v);
        assertThat(Files.readString(f, StandardCharsets.UTF_8)).startsWith("#").contains("TOOLKIT_HTTPS_PORT=8443\n");
        assertOwnerOnly(f);
        // A rewrite keeps it owner-only.
        if (!Home.WINDOWS) Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        EnvFile.write(f, Map.of("X", "1"));
        assertOwnerOnly(f);
        assertThat(EnvFile.read(f)).containsExactly(Map.entry("X", "1"));
        try (var s = Files.list(f.getParent())) {
            assertThat(s.map(p -> p.getFileName().toString())).as("no temp file left").containsExactly("toolkit.env");
        }
    }

    /** POSIX 600; on Windows the real ACL: the owner, LocalSystem (service mode) and nobody else, nothing inherited. */
    static void assertOwnerOnly(Path f) throws Exception {
        if (!Home.WINDOWS) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(f))).isEqualTo("rw-------");
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(f, AclFileAttributeView.class);
        String owner = view.getOwner().getName();
        List<AclEntry> acl = view.getAcl();
        assertThat(acl).as("ACL of " + f).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(e.principal().getName()).as("principal").satisfiesAnyOf(n -> assertThat(n).isEqualTo(owner), n -> assertThat(n).endsWith("\\SYSTEM"));
            assertThat(e.flags()).as("inherited or inheritable").isEmpty();
        });
        assertThat(acl).anySatisfy(e -> {
            assertThat(e.principal().getName()).isEqualTo(owner);
            assertThat(e.permissions()).containsAll(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA));
        });
        assertThat(acl).filteredOn(e -> e.principal().getName().endsWith("\\SYSTEM"))
                .allSatisfy(e -> assertThat(e.permissions()).contains(AclEntryPermission.READ_DATA).doesNotContain(AclEntryPermission.WRITE_DATA));
    }

    @Test
    void aValueWithANewlineCannotBeWritten() {
        assertThatThrownBy(() -> EnvFile.write(dir.resolve("e"), Map.of("A", "x\ny"))).hasMessageContaining("A");
        assertThatThrownBy(() -> EnvFile.write(dir.resolve("e"), Map.of("bad key", "x"))).hasMessageContaining("bad key");
    }

    @Test
    void aMissingFileReadsAsEmpty() {
        assertThat(EnvFile.read(dir.resolve("none.env"))).isEmpty();
    }

    @Test
    void realEnvironmentWinsOverTheFile() {
        Map<String, String> merged = EnvFile.merge(Map.of("A", "file", "B", "file"), Map.of("B", "real", "C", "real"));
        assertThat(merged).containsOnly(Map.entry("A", "file"), Map.entry("B", "real"), Map.entry("C", "real"));
    }

    /** The Windows branch: only the ACL it builds can be checked here (macOS has no AclFileAttributeView). */
    @Test
    void theWindowsAclGrantsTheOwnerEverythingAndNoOneElseAnything() {
        UserPrincipal owner = () -> "HOST\\someone";
        List<AclEntry> acl = EnvFile.ownerOnlyAcl(owner);
        assertThat(acl).hasSize(1);
        AclEntry e = acl.get(0);
        assertThat(e.type()).isEqualTo(AclEntryType.ALLOW);
        assertThat(e.principal()).isSameAs(owner);
        assertThat(e.permissions()).isEqualTo(EnumSet.allOf(AclEntryPermission.class));
        assertThat(e.flags()).as("nothing to inherit: it is a file").isEmpty();
    }
}
