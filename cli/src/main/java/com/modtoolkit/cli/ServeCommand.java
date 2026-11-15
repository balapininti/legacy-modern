package com.modtoolkit.cli;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Run the server: web UI, REST API and MCP on one HTTPS port (TOOLKIT_HTTPS_PORT, default 8443).")
public class ServeCommand implements Callable<Integer> {
    @Option(names = "--plain-http", description = "No TLS, on TOOLKIT_API_PORT (default 8080): for development, or behind a proxy that terminates TLS.")
    boolean plainHttp;

    @Override
    public Integer call() throws Exception {
        try {
            ToolkitApp.server(plainHttp);
        } catch (IllegalArgumentException e) {
            System.err.println("toolkit serve: " + e.getMessage());
            return 2;
        }
        new CountDownLatch(1).await();
        return 0;
    }
}
