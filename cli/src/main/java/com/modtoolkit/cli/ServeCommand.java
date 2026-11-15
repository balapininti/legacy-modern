package com.modtoolkit.cli;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Run the server: web UI, REST API and MCP on one HTTPS port (TOOLKIT_HTTPS_PORT, default 8443).")
public class ServeCommand implements Callable<Integer> {
    @Option(names = "--plain-http", description = "No TLS, on TOOLKIT_API_PORT (default 8080): for development, or behind a proxy that terminates TLS.")
    boolean plainHttp;

    /** Set by {@code toolkit start --foreground} in the server's environment: the launcher's process id. */
    public static final String LAUNCHER_PID = "TOOLKIT_LAUNCHER_PID";

    @Override
    public Integer call() throws Exception {
        watchLauncher(System.getenv(LAUNCHER_PID));
        try {
            ToolkitApp.server(plainHttp);
        } catch (IllegalArgumentException e) {
            System.err.println("toolkit serve: " + e.getMessage());
            return 2;
        }
        new CountDownLatch(1).await();
        return 0;
    }

    /**
     * In the foreground (service mode) the launcher stops the server when it ends, through a shutdown hook. A hook
     * does not run when the launcher is killed (Task Manager, or a service manager giving up; on Windows the JDK can
     * only terminate a process), so the server also ends itself when its launcher is gone, instead of running on
     * unseen with nothing to stop it. The next start finds the database running and uses it.
     */
    static void watchLauncher(String pid) {
        if (pid == null || pid.isBlank()) return;
        try {
            ProcessHandle.of(Long.parseLong(pid.strip())).ifPresentOrElse(
                    h -> h.onExit().thenRun(() -> {
                        System.err.println("toolkit serve: the launcher (pid " + pid.strip() + ") has ended; stopping");
                        System.exit(0);
                    }),
                    () -> System.exit(0));
        } catch (NumberFormatException e) {
            System.err.println("toolkit serve: ignoring " + LAUNCHER_PID + "=" + pid);
        }
    }
}
