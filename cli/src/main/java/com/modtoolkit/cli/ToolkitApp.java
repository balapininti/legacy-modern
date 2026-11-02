package com.modtoolkit.cli;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The one Spring application behind every command and service profile. */
@SpringBootApplication(scanBasePackages = "com.modtoolkit")
public class ToolkitApp {
    /** The REST API behind the web UI; blocks until stopped. */
    public static ConfigurableApplicationContext api(String... args) {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("api").logStartupInfo(false).run(args);
    }

    /** The shared MCP server over Streamable HTTP; blocks until stopped. */
    public static ConfigurableApplicationContext mcpHttp(String... args) {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("mcp").logStartupInfo(false).run(args);
    }

    /** A developer's local server on stdin and stdout. Only the protocol may touch stdout. */
    public static ConfigurableApplicationContext mcpStdio() {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("stdio").web(WebApplicationType.NONE).logStartupInfo(false).run();
    }

    public static ConfigurableApplicationContext batch() {
        return batch(2);
    }

    /** @param threads workers that may each hold a member transaction and a node upsert connection at once */
    public static ConfigurableApplicationContext batch(int threads) {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("batch").web(WebApplicationType.NONE).logStartupInfo(false)
                .properties("spring.datasource.hikari.maximum-pool-size=" + (threads * 2 + 4)).run();
    }
}
