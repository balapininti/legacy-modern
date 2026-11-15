package com.modtoolkit.cli.launch;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.cli.ToolkitMain;
import com.modtoolkit.core.config.ConfigValidator;
import com.modtoolkit.core.config.ToolkitConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

class LauncherTest {
    @TempDir Path dir;

    Home installed() throws Exception {
        Home h = new Home(dir.resolve("Tool Kit"));
        Files.createDirectories(h.pgData());
        Files.writeString(h.pgData().resolve("PG_VERSION"), "17\n");
        return h;
    }

    @Test
    void homeComesFromThePropertyThenTheEnvironment() {
        assertThat(Home.detect(Map.of(), null)).isEmpty();
        assertThat(Home.detect(Map.of("TOOLKIT_HOME", " "), null)).isEmpty();
        assertThat(Home.detect(Map.of("TOOLKIT_HOME", dir.toString()), null)).contains(new Home(dir));
        // The .cmd passes -DTOOLKIT_HOME="%~dp0." so no backslash sits before the closing quote.
        assertThat(Home.detect(Map.of("TOOLKIT_HOME", "/elsewhere"), dir + "/.")).contains(new Home(dir));
    }

    @Test
    void theLayoutIsTheBundles() {
        Home h = new Home(dir);
        assertThat(h.envFile()).isEqualTo(dir.resolve("config/toolkit.env"));
        assertThat(h.config()).isEqualTo(dir.resolve("config/toolkit.yaml"));
        assertThat(h.pgData()).isEqualTo(dir.resolve("data/pg"));
        assertThat(h.keystore()).isEqualTo(dir.resolve("data/tls/toolkit.p12"));
        assertThat(h.log()).isEqualTo(dir.resolve("data/logs/toolkit.log"));
        assertThat(h.pidFile()).isEqualTo(dir.resolve("data/toolkit.pid"));
        assertThat(h.pgHome(Map.of())).isEqualTo(dir.resolve("runtime/pgsql"));
        assertThat(h.pgHome(Map.of("TOOLKIT_PG_HOME", "/opt/pg"))).isEqualTo(Path.of("/opt/pg"));
    }

    @Test
    void javaIsTheBundledRuntimeElseThisOne() throws Exception {
        Home h = new Home(dir);
        assertThat(h.java()).isEqualTo(Home.currentJava());
        Path bundled = dir.resolve("runtime/jre/bin").resolve(Home.WINDOWS ? "java.exe" : "java");
        Files.createDirectories(bundled.getParent());
        Files.writeString(bundled, "");
        assertThat(h.java()).isEqualTo(bundled);
        assertThat(h.keytool()).isEqualTo(bundled.resolveSibling(Home.WINDOWS ? "keytool.exe" : "keytool"));
    }

    @Test
    void onlyOrdinaryCommandsInAnInstalledHomeGoToAChild() throws Exception {
        Home h = installed();
        Optional<Home> oh = Optional.of(h);
        assertThat(Launcher.delegates(oh, new String[] {"doctor"}, Map.of())).isTrue();
        assertThat(Launcher.delegates(oh, new String[] {"token", "--user", "x"}, Map.of())).isTrue();
        for (String own : List.of("setup", "start", "stop", "--help", "-h", "--version", "-V")) {
            assertThat(Launcher.delegates(oh, new String[] {own}, Map.of())).as(own).isFalse();
        }
        assertThat(Launcher.delegates(oh, new String[] {}, Map.of())).isFalse();
        assertThat(Launcher.delegates(oh, new String[] {"doctor"}, Map.of(Launcher.CHILD_MARKER, "1"))).as("the child itself").isFalse();
        assertThat(Launcher.delegates(Optional.empty(), new String[] {"doctor"}, Map.of())).as("developer use").isFalse();
        assertThat(Launcher.delegates(Optional.of(new Home(dir.resolve("empty"))), new String[] {"doctor"}, Map.of())).as("before setup").isFalse();
    }

    @Test
    void theEnvironmentLayersDefaultsThenTheFileThenRealVariables() throws Exception {
        Home h = installed();
        EnvFile.write(h.envFile(), Map.of("TOOLKIT_PG_PORT", "6000", "TOOLKIT_DB_PASSWORD", "file", "TOOLKIT_HTTPS_PORT", "9443"));
        Map<String, String> env = Launcher.environment(h, Map.of("TOOLKIT_HTTPS_PORT", "10443", "PATH", "/bin"));
        assertThat(env).containsEntry("TOOLKIT_HTTPS_PORT", "10443").containsEntry("TOOLKIT_DB_PASSWORD", "file")
                .containsEntry("TOOLKIT_PG_PORT", "6000").containsEntry("PATH", "/bin")
                .containsEntry("TOOLKIT_DB_URL", "jdbc:postgresql://127.0.0.1:6000/toolkit").containsEntry("TOOLKIT_DB_USER", "toolkit")
                .containsEntry("TOOLKIT_HOME", h.root().toString())
                .containsEntry("TOOLKIT_CONFIG", h.config().toString())
                .containsEntry("TOOLKIT_TLS_KEYSTORE", h.keystore().toString());
        assertThat(Launcher.environment(h, Map.of()).get("TOOLKIT_HTTPS_PORT")).isEqualTo("9443");
        assertThat(Launcher.environment(new Home(dir.resolve("x")), Map.of())).containsEntry("TOOLKIT_HTTPS_PORT", "8443").containsEntry("TOOLKIT_PG_PORT", "55432");
    }

    @Test
    void theChildIsMarkedAndNeverSeesTheDatabaseSuperuserPassword() throws Exception {
        Home h = installed();
        Map<String, String> child = Launcher.childEnvironment(Map.of("TOOLKIT_PG_SUPERUSER_PASSWORD", "s", "TOOLKIT_DB_PASSWORD", "a"));
        assertThat(child).containsEntry(Launcher.CHILD_MARKER, "1").containsEntry("TOOLKIT_DB_PASSWORD", "a").doesNotContainKey("TOOLKIT_PG_SUPERUSER_PASSWORD");
        List<String> cmd = Launcher.javaCommand(h, Map.of("TOOLKIT_JAVA_OPTS", "-Xmx1g  -Dx=y"), Path.of("/x/toolkit.jar"), "doctor", "--a b");
        assertThat(cmd).containsExactly(h.java().toString(), "-Xmx1g", "-Dx=y", "-jar", "/x/toolkit.jar", "doctor", "--a b");
    }

    @Command(name = "t")
    static class WithConfig {
        @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;
        @Option(names = "--other", defaultValue = "o") String other;
    }

    @Test
    void inTheChildConfigDefaultsToTheInstallsFile() {
        WithConfig a = new WithConfig();
        CommandLine cl = new CommandLine(a);
        ToolkitMain.configDefaults(cl, Map.of(Launcher.CHILD_MARKER, "1", "TOOLKIT_CONFIG", "/home/config/toolkit.yaml"));
        cl.parseArgs();
        assertThat(a.config).isEqualTo(Path.of("/home/config/toolkit.yaml"));
        assertThat(a.other).isEqualTo("o");

        WithConfig b = new WithConfig();
        cl = new CommandLine(b);
        ToolkitMain.configDefaults(cl, Map.of(Launcher.CHILD_MARKER, "1", "TOOLKIT_CONFIG", "/home/config/toolkit.yaml"));
        cl.parseArgs("-c", "mine.yaml");
        assertThat(b.config).as("an explicit -c wins").isEqualTo(Path.of("mine.yaml"));

        WithConfig c = new WithConfig();
        cl = new CommandLine(c);
        ToolkitMain.configDefaults(cl, Map.of("TOOLKIT_CONFIG", "/home/config/toolkit.yaml"));
        cl.parseArgs();
        assertThat(c.config).as("developer use is unchanged").isEqualTo(Path.of("toolkit.yaml"));
    }

    @Test
    void theWrittenConfigLoadsAndValidatesWithAWindowsDrivePath() throws Exception {
        Path yaml = dir.resolve("toolkit.yaml");
        Files.writeString(yaml, SetupCommand.yaml("O'Brien & Co: \"x\"", "C:\\Users\\a b\\src's", "D:\\modern"));
        ToolkitConfig c = ToolkitConfig.load(yaml);
        assertThat(c.client()).isEqualTo("O'Brien & Co: \"x\"");
        assertThat(c.source().root()).isEqualTo("C:/Users/a b/src's");
        assertThat(c.target().localPath()).isEqualTo("D:/modern");
        assertThat(c.target().indexExisting()).isTrue();
        assertThat(ConfigValidator.validate(c)).isEmpty();

        Files.writeString(yaml, SetupCommand.yaml("acme", "/data/src", null));
        c = ToolkitConfig.load(yaml);
        assertThat(c.source().root()).isEqualTo("/data/src");
        assertThat(c.target()).isNull();
        assertThat(ConfigValidator.validate(c)).isEmpty();
    }

    @Test
    void secretsAreLongAndSafeInAnEnvFileAndAUrl() {
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            String s = SetupCommand.secret();
            assertThat(s).matches("[A-Za-z0-9_-]{32}");
            seen.merge(s, 1, Integer::sum);
        }
        assertThat(seen).hasSize(50);
    }

    @Test
    void hostnamesAreUsableInACertificate() {
        assertThat(SetupCommand.hostname(Map.of("COMPUTERNAME", "WIN-ABC123"))).isEqualTo("win-abc123");
        assertThat(SetupCommand.hostname(Map.of("COMPUTERNAME", "bad name!"))).matches("[a-z0-9.-]+");
    }
}
