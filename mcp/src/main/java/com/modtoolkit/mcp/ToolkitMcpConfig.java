package com.modtoolkit.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.security.TokenService.Identity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class ToolkitMcpConfig {
    private static final String IDENTITY = "toolkit.identity";

    /** The tool's result is JSON text either way; an error result carries its code, message and hint in it. */
    private static final class Callback implements ToolCallback {
        private final ToolDef tool;
        private final ToolRuntime runtime;

        Callback(ToolDef tool, ToolRuntime runtime) {
            this.tool = tool;
            this.runtime = runtime;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(tool.name()).description(tool.description()).inputSchema(tool.inputSchema()).build();
        }

        @Override
        public String call(String input) {
            return call(input, null);
        }

        @Override
        public String call(String input, ToolContext ctx) {
            ToolRuntime.Outcome o = runtime.call(tool, input, identityOf(ctx));
            if (o.error()) throw new ToolFailure(o.json());
            return o.json();
        }
    }

    /** McpToolUtils turns any exception into an MCP error result whose text is this message. */
    static final class ToolFailure extends RuntimeException {
        ToolFailure(String json) {
            super(json, null, false, false);
        }
    }

    static Identity identityOf(ToolContext ctx) {
        if (ctx != null) {
            McpSyncServerExchange ex = McpToolUtils.getMcpExchange(ctx).orElse(null);
            if (ex != null && ex.transportContext() != null && ex.transportContext().get(IDENTITY) instanceof Identity id) {
                return id;
            }
        }
        // stdio: a developer's own process, already trusted by the operating system account running it.
        String user = System.getenv().getOrDefault("TOOLKIT_USER", System.getProperty("user.name", "local"));
        return new Identity(user + "@stdio", List.of("developer"));
    }

    @Bean
    public ToolCallbackProvider toolkitTools(List<ToolSet> sets, ToolRuntime runtime) {
        List<ToolCallback> all = new ArrayList<>();
        for (ToolSet s : sets) for (ToolDef d : s.tools()) all.add(new Callback(d, runtime));
        ToolCallback[] arr = all.toArray(new ToolCallback[0]);
        return () -> arr;
    }

    @Bean
    public ToolSet legacyToolSet(LegacyTools t) {
        return t::tools;
    }

    /**
     * Same transport as the auto-configuration, plus the identity captured on the servlet thread, where the security
     * filter put it: the tool itself may run on another thread, where SecurityContextHolder would be empty.
     */
    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "false", matchIfMissing = true)
    public WebMvcStreamableServerTransportProvider streamableTransport(ObjectMapper mapper, McpServerStreamableHttpProperties props) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .mcpEndpoint(props.getMcpEndpoint())
                .keepAliveInterval(props.getKeepAliveInterval())
                .disallowDelete(props.isDisallowDelete())
                .contextExtractor(req -> {
                    Authentication a = SecurityContextHolder.getContext().getAuthentication();
                    if (a == null || !a.isAuthenticated()) return McpTransportContext.EMPTY;
                    List<String> roles = a.getAuthorities().stream().map(GrantedAuthority::getAuthority).map(r -> r.replaceFirst("^ROLE_", "")).collect(Collectors.toList());
                    return McpTransportContext.create(Map.of(IDENTITY, new Identity(a.getName(), roles)));
                })
                .build();
    }
}
