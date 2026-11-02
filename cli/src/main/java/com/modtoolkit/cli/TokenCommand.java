package com.modtoolkit.cli;

import com.modtoolkit.core.security.TokenService;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "token", description = "Issue an MCP bearer token. The Admin page does the same in the web UI.")
public class TokenCommand implements Callable<Integer> {
    @Option(names = "--user", required = true) String user;
    @Option(names = "--role", defaultValue = "developer", split = ",") List<String> roles;
    @Option(names = "--name", defaultValue = "cli") String name;

    @Override
    public Integer call() {
        try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
            System.out.println(ctx.getBean(TokenService.class).issue(user, roles, name));
        }
        return 0;
    }
}
