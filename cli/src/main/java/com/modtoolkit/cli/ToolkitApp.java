package com.modtoolkit.cli;

import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** The one Spring application behind every command and service profile. */
@SpringBootApplication(scanBasePackages = "com.modtoolkit")
public class ToolkitApp {
    /**
     * The one server an install runs: web UI, REST API and MCP in a single context, so start-up work such as the
     * first-run admin happens once. HTTPS unless {@code plainHttp}; a missing or half-set certificate fails here,
     * before Spring starts, with the variables to set.
     */
    public static ConfigurableApplicationContext server(boolean plainHttp, String... args) {
        Map<String, Object> props = plainHttp ? Map.of("server.port", "${TOOLKIT_API_PORT:8080}") : ServerTls.properties(new StandardEnvironment());
        // First, so the certificate the environment names is the one used, whatever a config file says.
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("server").logStartupInfo(false)
                .initializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("toolkit-serve", props))).run(args);
    }

    /** MCP alone over plain Streamable HTTP, for those who run it apart from the server. */
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
