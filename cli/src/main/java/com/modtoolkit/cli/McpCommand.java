package com.modtoolkit.cli;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;

@Command(name = "mcp", description = "Run the MCP server.", subcommands = {McpCommand.Http.class, McpCommand.Stdio.class})
public class McpCommand {
    @Command(name = "http", description = "The shared server over Streamable HTTP.")
    static class Http implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            ToolkitApp.mcpHttp();
            new CountDownLatch(1).await();
            return 0;
        }
    }

    @Command(name = "stdio", description = "A local server on stdin and stdout, against the shared database.")
    static class Stdio implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            ToolkitApp.mcpStdio();
            new CountDownLatch(1).await();
            return 0;
        }
    }
}
