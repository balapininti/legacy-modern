package com.modtoolkit.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Free text is rejected, and so is any answer citing a line that does not exist or a paragraph the AST does not have. */
public final class ArtifactValidator {
    public static final class Invalid extends RuntimeException {
        public Invalid(String m) {
            super(m);
        }
    }

    /** What is true of the unit, from the parser. */
    public record Facts(int lineMin, int lineMax, Set<String> paragraphs, Set<String> dataItems) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();

    private ArtifactValidator() {}

    public static String schemaText(String kind) {
        try (InputStream in = ArtifactValidator.class.getResourceAsStream("/schemas/" + kind + ".json")) {
            if (in == null) throw new IllegalArgumentException("no schema for " + kind);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonSchema schema(String kind) {
        return SCHEMAS.computeIfAbsent(kind, k -> JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaText(k)));
    }

    /** @return the validated JSON, with names the AST does not know moved aside instead of trusted */
    public static ObjectNode validate(String kind, String text, Facts facts) {
        JsonNode n = parse(text);
        Set<ValidationMessage> errors = schema(kind).validate(n);
        if (!errors.isEmpty()) {
            throw new Invalid("schema: " + errors.stream().limit(5).map(ValidationMessage::getMessage).collect(Collectors.joining("; ")));
        }
        ObjectNode o = (ObjectNode) n;
        switch (kind) {
            case "summary" -> o.get("evidence").forEach(e -> range(e, facts, "evidence"));
            case "rule" -> o.get("rules").forEach(r -> {
                range(r, facts, r.get("rule_id").asText());
                String para = r.path("paragraph").asText(null);
                if (para != null && !facts.paragraphs().isEmpty() && !facts.paragraphs().contains(para.toUpperCase(Locale.ROOT))) {
                    throw new Invalid(r.get("rule_id").asText() + " cites paragraph " + para + ", which is not in this program");
                }
                unverified((ObjectNode) r, facts);
            });
            case "migration_note" -> o.get("items").forEach(i -> range(i, facts, i.get("feature").asText()));
            case "dictionary" -> o.get("entries").forEach(e -> {
                int line = e.get("line").asInt();
                if (line < facts.lineMin() || line > facts.lineMax()) throw new Invalid(e.get("data_item").asText() + " cites line " + line + ", outside " + facts.lineMin() + "-" + facts.lineMax());
                if (!facts.dataItems().isEmpty() && !facts.dataItems().contains(e.get("data_item").asText().toUpperCase(Locale.ROOT))) {
                    throw new Invalid("no data item named " + e.get("data_item").asText());
                }
            });
            case "job_narrative", "screen_flow" -> range(o, facts, kind);
            default -> { }
        }
        return o;
    }

    private static JsonNode parse(String text) {
        String t = text == null ? "" : text.strip();
        // Models often wrap JSON in a code fence, despite being told not to; that is not the free text we mean to reject.
        if (t.startsWith("```")) t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        try {
            JsonNode n = JSON.readTree(t);
            if (n == null || !n.isObject()) throw new Invalid("the answer is not a JSON object");
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new Invalid("the answer is not JSON");
        }
    }

    private static void range(JsonNode n, Facts f, String what) {
        int s = n.get("line_start").asInt(), e = n.get("line_end").asInt();
        if (s > e) throw new Invalid(what + " has line_start after line_end");
        if (s < f.lineMin() || e > f.lineMax()) throw new Invalid(what + " cites lines " + s + "-" + e + ", outside " + f.lineMin() + "-" + f.lineMax());
    }

    private static void unverified(ObjectNode rule, Facts f) {
        if (f.dataItems().isEmpty()) return;
        List<String> ok = new ArrayList<>(), not = new ArrayList<>();
        rule.get("data_items").forEach(d -> (f.dataItems().contains(d.asText().toUpperCase(Locale.ROOT)) ? ok : not).add(d.asText()));
        if (not.isEmpty()) return;
        ArrayNode good = rule.putArray("data_items");
        ok.forEach(good::add);
        ArrayNode bad = rule.putArray("unverified_data_items");
        not.forEach(bad::add);
    }
}
