package com.modtoolkit.cli.launch;

import com.modtoolkit.core.local.LocalPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "stop", description = "Stop this install's server, then its database. Does nothing when neither runs.")
public class StopCommand implements Callable<Integer> {
    static final Duration GRACE = Duration.ofSeconds(60);

    @Override
    public Integer call() {
        Home home = Home.detect().orElse(null);
        if (home == null) {
            System.err.println("toolkit stop: run it from the install folder (stop.cmd); TOOLKIT_HOME is not set");
            return 2;
        }
        return stop(home, Launcher.environment(home, System.getenv())) ? 0 : 1;
    }

    /** False, having said why, when something would not stop. */
    static boolean stop(Home home, Map<String, String> env) {
        boolean any = false;
        Optional<ProcessHandle> server = Launcher.server(home);
        if (server.isPresent()) {
            System.out.println("stopping the server (pid " + server.get().pid() + ")");
            Launcher.stopProcess(server.get(), GRACE);
            if (server.get().isAlive()) {
                System.err.println("toolkit stop: the server (pid " + server.get().pid() + ") did not stop");
                return false;
            }
            any = true;
        }
        try {
            Files.deleteIfExists(home.pidFile());
        } catch (IOException e) {
            System.err.println("toolkit stop: cannot delete " + home.pidFile() + ": " + e.getMessage());
        }
        if (home.installed()) {
            try {
                LocalPostgres pg = Launcher.postgres(home, env);
                if (pg.running()) {
                    System.out.println("stopping the database");
                    pg.stop();
                    any = true;
                }
            } catch (RuntimeException e) {
                System.err.println("toolkit stop: the database did not stop: " + e.getMessage());
                return false;
            }
        }
        System.out.println(any ? "stopped" : "nothing was running");
        return true;
    }
}
