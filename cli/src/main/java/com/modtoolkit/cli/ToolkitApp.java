package com.modtoolkit.cli;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The one Spring application behind every command and service profile. */
@SpringBootApplication(scanBasePackages = "com.modtoolkit")
public class ToolkitApp {
    /** The shared MCP server over Streamable HTTP; blocks until stopped. */
    public static ConfigurableApplicationContext mcpHttp(String... args) {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("mcp").logStartupInfo(false).run(args);
    }

    /** A developer's local server on stdin and stdout. Only the protocol may touch stdout. */
    public static ConfigurableApplicationContext mcpStdio() {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("stdio").web(WebApplicationType.NONE).logStartupInfo(false).run();
    }

    public static ConfigurableApplicationContext batch() {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("batch").web(WebApplicationType.NONE).logStartupInfo(false).run();
    }
}
