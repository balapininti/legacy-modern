package com.modtoolkit.cli.launch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.regex.Pattern;

/**
 * {@code config/toolkit.env}: the install's secrets and settings, which the launcher hands to its child processes as
 * environment variables. A real environment variable of the same name wins over the file.
 *
 * <p>Format, one setting per line: {@code NAME=value}. NAME is letters, digits and underscores, not starting with a
 * digit; spaces around it are ignored. Everything after the first {@code =} is the value, exactly as written: spaces,
 * further {@code =}, {@code %}, {@code $} and quotes are part of it, nothing is expanded or unquoted. A value cannot
 * span lines. Blank lines and lines whose first non-blank character is {@code #} are ignored. A later line for the
 * same NAME wins. UTF-8; a leading byte-order mark and CRLF line ends (Notepad) are accepted.
 *
 * <p>Writing restricts the file to the current user: POSIX mode 600, or on Windows an ACL giving the owner full
 * control and LocalSystem read (for service mode), with inherited entries removed.
 */
public final class EnvFile {
    static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final String HEADER = """
            # Toolkit settings and secrets, passed to the toolkit's processes as environment variables.
            # A real environment variable of the same name wins over this file.
            # One NAME=value per line. Everything after the first = is the value, exactly as written:
            # no quotes are removed and nothing (%VAR%, $VAR) is expanded. Lines starting with # are ignored.
            # Readable by the installing user only; keep it that way.
            """;

    private EnvFile() {}

    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("﻿")) line = line.substring(1);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = line.indexOf('=');
            String name = eq < 0 ? "" : line.substring(0, eq).strip();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("line " + (i + 1) + " is not NAME=value");
            }
            out.put(name, line.substring(eq + 1));
        }
        return out;
    }

    /** The settings in {@code file}; none when it does not exist. */
    public static Map<String, String> read(Path file) {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        } catch (IllegalArgumentException e) {
            // Never echo the line: it may hold a secret.
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    /** The file's settings with every real environment variable laid over them. */
    public static Map<String, String> merge(Map<String, String> file, Map<String, String> real) {
        Map<String, String> out = new LinkedHashMap<>(file);
        out.putAll(real);
        return out;
    }

    /** Replaces {@code file} with {@code values}, readable by the current user only from the moment it holds them. */
    public static void write(Path file, Map<String, String> values) {
        StringBuilder b = new StringBuilder(HEADER);
        values.forEach((k, v) -> {
            if (!NAME.matcher(k).matches()) throw new IllegalArgumentException("not a variable name: " + k);
            if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) throw new IllegalArgumentException("the value of " + k + " has a line break");
            b.append(k).append('=').append(v).append('\n');
        });
        Path dir = file.toAbsolutePath().getParent();
        try {
            Files.createDirectories(dir);
            Path tmp;
            if (posix()) {
                tmp = Files.createTempFile(dir, ".toolkit-env", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                tmp = Files.createTempFile(dir, ".toolkit-env", ".tmp");
                restrictToOwner(tmp);
            }
            try {
                Files.writeString(tmp, b, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            restrictToOwner(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /**
     * POSIX 600; on Windows the owner with full control and no inherited entries, plus read for the operating system
     * itself (LocalSystem, S-1-5-18), which runs the optional Windows service (service\toolkit.xml). LocalSystem can
     * read every file anyway; the entry only saves it from needing a privilege to do so.
     */
    public static void restrictToOwner(Path file) throws IOException {
        if (posix()) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("cannot restrict " + file + " to its owner: the file system has neither POSIX permissions nor ACLs");
        }
        // setAcl replaces the whole DACL, inherited entries included.
        acl.setAcl(ownerOnlyAcl(acl.getOwner()));
        // It does not mark the DACL protected, so a later change on the folder could flow back in; icacls does.
        protectWithIcacls(file);
    }

    static List<AclEntry> ownerOnlyAcl(UserPrincipal owner) {
        return List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
    }

    private static void protectWithIcacls(Path file) {
        try {
            // By SID: account names are translated on non-English Windows.
            Process p = new ProcessBuilder("icacls", file.toString(), "/inheritance:r", "/grant", "*S-1-5-18:(R)").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException e) {
            // Best effort: the ACL set above already holds only the owner.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
