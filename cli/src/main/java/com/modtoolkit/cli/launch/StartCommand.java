package com.modtoolkit.cli.launch;

import com.modtoolkit.core.local.LocalPostgres;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "start", description = "Start this install's database, then the server: web UI, REST API and MCP on one HTTPS port.")
public class StartCommand implements Callable<Integer> {
    @Option(names = "--foreground", description = "Stay attached to the server and stop the database when it exits (service mode); Ctrl+C stops both.")
    boolean foreground;

    @Option(names = "--timeout", defaultValue = "180", description = "Seconds to wait for the server to answer (default: ${DEFAULT-VALUE})")
    int timeout;

    @Override
    public Integer call() throws Exception {
        Home home = Home.detect().orElse(null);
        if (home == null) {
            System.err.println("toolkit start: run it from the install folder (start.cmd); TOOLKIT_HOME is not set");
            return 2;
        }
        if (!home.installed()) {
            System.err.println("toolkit start: not set up yet; run: toolkit setup --source <folder with the mainframe source>");
            return 2;
        }
        Map<String, String> env = Launcher.environment(home, System.getenv());
        int port = Launcher.port(env, "TOOLKIT_HTTPS_PORT");
        SSLContext trust;
        try {
            trust = Launcher.trust(env);
        } catch (Exception e) {
            System.err.println("toolkit start: cannot read the server certificate (TOOLKIT_TLS_KEYSTORE or TOOLKIT_TLS_CERT): " + e.getMessage());
            return 1;
        }
        String url = "https://" + SetupCommand.hostname(System.getenv()) + ":" + port + "/";

        Optional<ProcessHandle> running = Launcher.server(home);
        if (running.isPresent()) {
            long pid = running.get().pid();
            if (waitHealthy(port, trust, running.get(), Duration.ofSeconds(timeout))) {
                if (foreground) {
                    System.err.println("toolkit start: already running in the background (pid " + pid + "); run toolkit stop first");
                    return 1;
                }
                System.out.println("toolkit is already running (pid " + pid + "): " + url);
                return 0;
            }
            System.err.println("toolkit start: a server (pid " + pid + ") is running but does not answer on port " + port
                    + "; run toolkit stop, then start again. Log: " + home.log());
            return 1;
        }
        // Left by a server that crashed or was killed.
        Files.deleteIfExists(home.pidFile());
        if (!Launcher.portFree(port)) {
            System.err.println("toolkit start: port " + port + " is already in use by another program; stop it,"
                    + " or set TOOLKIT_HTTPS_PORT to a free port in " + home.envFile());
            return 1;
        }

        LocalPostgres pg = Launcher.postgres(home, env);
        try {
            pg.start(Launcher.PG_START);
        } catch (RuntimeException e) {
            System.err.println("toolkit start: the database did not start: " + e.getMessage());
            return 1;
        }
        System.out.println("database running on 127.0.0.1:" + pg.port());

        List<String> cmd = Launcher.javaCommand(home, env, home.jar(), "serve");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(home.root().toFile());
        Launcher.replaceEnvironment(pb, Launcher.childEnvironment(env));
        if (foreground) {
            pb.inheritIO();
        } else {
            // javaw has no console, so closing the window start.cmd ran in does not end the server.
            Path javaw = home.java().resolveSibling("javaw.exe");
            if (Home.WINDOWS && Files.isRegularFile(javaw)) cmd.set(0, javaw.toString());
            pb.command(cmd);
            Launcher.rotate(home.log());
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.log().toFile()))
                    .redirectInput(ProcessBuilder.Redirect.from(new File(Home.WINDOWS ? "NUL" : "/dev/null")));
        }
        Process server = pb.start();
        Launcher.writePid(home, server.toHandle());
        Cleanup cleanup = new Cleanup(home, server, pg);
        if (foreground) Runtime.getRuntime().addShutdownHook(new Thread(cleanup::run, "toolkit-stop"));

        if (!waitHealthy(port, trust, server.toHandle(), Duration.ofSeconds(timeout))) {
            if (cleanup.started()) return 130; // Ctrl+C or a service stop while starting: the hook is stopping everything
            System.err.println("toolkit start: the server " + (server.isAlive() ? "did not answer within " + timeout + " s" : "exited with code " + server.exitValue())
                    + (foreground ? "" : "; last lines of " + home.log() + ":\n" + Launcher.tail(home.log(), 40)));
            cleanup.run();
            return 1;
        }
        System.out.println("toolkit is running: " + url);
        if (!foreground) {
            System.out.println("log: " + home.log());
            System.out.println("stop it with stop.cmd (toolkit stop)");
            return 0;
        }
        System.out.println("Ctrl+C or toolkit stop ends it");
        int code = server.waitFor();
        cleanup.run();
        return code;
    }

    static boolean waitHealthy(int port, SSLContext trust, ProcessHandle server, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (server.isAlive()) {
            if (Launcher.healthy(port, trust)) return true;
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(500);
        }
        return false;
    }

    /** Stops the server and the database once, whether the server ended, start gave up, or the JVM is shutting down. */
    static final class Cleanup {
        private final Home home;
        private final Process server;
        private final LocalPostgres pg;
        private boolean done;

        Cleanup(Home home, Process server, LocalPostgres pg) {
            this.home = home;
            this.server = server;
            this.pg = pg;
        }

        synchronized boolean started() {
            return done;
        }

        synchronized void run() {
            if (done) return;
            done = true;
            Launcher.stopProcess(server.toHandle(), StopCommand.GRACE);
            try {
                if (Launcher.server(home).isEmpty()) Files.deleteIfExists(home.pidFile());
            } catch (IOException ignored) {
                // start removes a stale pid file itself
            }
            try {
                pg.stop();
            } catch (RuntimeException e) {
                System.err.println("toolkit: the database did not stop: " + e.getMessage());
            }
        }
    }
}
