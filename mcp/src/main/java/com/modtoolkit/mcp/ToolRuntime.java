package com.modtoolkit.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modtoolkit.core.Hashes;
import com.modtoolkit.core.audit.AuditLog;
import com.modtoolkit.core.security.TokenService.Identity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Everything every tool must do, in one place, so no tool can forget it. */
@Service
public class ToolRuntime {
    private static final Logger log = LoggerFactory.getLogger(ToolRuntime.class);

    public record Outcome(String json, boolean error) {}

    private final ObjectMapper json = new ObjectMapper();
    private final Paginator paginator = new Paginator(json);
    private final AuditLog audit;

    public ToolRuntime(AuditLog audit) {
        this.audit = audit;
    }

    public Outcome call(ToolDef tool, String rawArgs, Identity who) {
        long t0 = System.nanoTime();
        String hash = Hashes.sha256(String.valueOf(rawArgs));
        String outcome = "ok";
        try {
            JsonNode args = parse(rawArgs);
            if (!who.has("admin", "developer")) {
                throw new ToolError("FORBIDDEN", "the developer role is required for " + tool.name(), "ask an admin to grant the developer role");
            }
            String cursor = args.path("cursor").asText(null);
            ObjectNode key = args.deepCopy();
            key.remove("cursor");
            List<?> items = tool.handler().run(args, who);
            String callKey = Hashes.sha256(tool.name() + key).substring(0, 16);
            return new Outcome(paginator.page(items, cursor, callKey), false);
        } catch (ToolError e) {
            outcome = e.code();
            return new Outcome(errorJson(e.code(), e.getMessage(), e.hint()), true);
        } catch (Exception e) {
            // Logged here for operators; the caller gets no stack trace and no SQL.
            log.error("tool {} failed", tool.name(), e);
            outcome = "INTERNAL";
            return new Outcome(errorJson("INTERNAL", "the tool failed unexpectedly", "the error is in the server log; try again or tell your modernization lead"), true);
        } finally {
            try {
                audit.record(who.username(), "tool_call", tool.name(), hash, (System.nanoTime() - t0) / 1_000_000, null, outcome);
            } catch (RuntimeException e) {
                log.error("audit write failed for {}", tool.name(), e);
            }
        }
    }

    private JsonNode parse(String raw) {
        try {
            JsonNode n = raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
            if (!n.isObject()) throw new ToolError("BAD_ARGUMENT", "arguments must be a JSON object", "see the tool's input schema");
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ToolError("BAD_ARGUMENT", "arguments are not valid JSON", "see the tool's input schema");
        }
    }

    private String errorJson(String code, String message, String hint) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        e.put("hint", hint);
        try {
            return json.writeValueAsString(Map.of("error", e));
        } catch (Exception x) {
            return "{\"error\":{\"code\":\"INTERNAL\",\"message\":\"error\",\"hint\":\"\"}}";
        }
    }
}
