package com.modtoolkit.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.modtoolkit.core.security.TokenService.Identity;
import java.util.List;

/** One frozen tool. Handlers return ordered items; paging, the cap, roles, errors and audit are the runtime's job. */
public record ToolDef(String name, String description, String inputSchema, boolean writes, Handler handler) {
    @FunctionalInterface
    public interface Handler {
        List<?> run(JsonNode args, Identity who) throws Exception;
    }
}
