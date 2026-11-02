package com.modtoolkit.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.security.TokenService.Identity;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MCP prompts and resources. Resources go through the same tools, so paging aside they carry the same rules. */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class McpExtras {
    private static final ObjectMapper JSON = new ObjectMapper();

    private record P(String name, String description, String argument, String argDescription, String text) {}

    private static final List<P> PROMPTS = List.of(
            new P("analyze_program", "Explain what a COBOL program does, from the toolkit's facts", "program", "Program name",
                    "Explain COBOL program %s. Call get_program with include summary, rules, callers, callees, tables, maps and jcl, and get_source for anything you need to read. Cite member and line for every claim. Say what the tools do not know rather than guessing."),
            new P("plan_migration", "Plan the rewrite of a program, in order, with its dependencies", "program", "Program name",
                    "Plan the migration of %s to Java. Call get_program, who_calls, calls_from, table_usage, job_flow, get_modern_impl, gap_report and deviations. List what exists already, what is missing, the order to build it in, the risks from migration_note, and which callers have to move with it. Do not plan to undo a registered deviation."),
            new P("translate_program", "Translate a program under this repository's conventions", "program", "Program name",
                    "Translate COBOL program %s to Java following the repository's instructions. First call get_program with include source, rules, data and migration_note, then deviations and get_modern_impl, then get_copybook for each record with expanded_for. Implement every rule, commenting its rule id. A registered deviation is never reverted to legacy behavior. Record your choices with record_decision."),
            new P("write_characterization_tests", "Write tests that prove the Java behaves like the mainframe", "program", "Program name",
                    "Write characterization tests for %s. Use migration_status for what fixtures exist and deviations for the approved differences. Never change an expected value to make a test pass: a difference that is not registered is a bug or a candidate for record_deviation."));

    @Bean
    public List<SyncPromptSpecification> toolkitPrompts() {
        List<SyncPromptSpecification> out = new ArrayList<>();
        for (P p : PROMPTS) {
            var prompt = new McpSchema.Prompt(p.name(), p.description(), List.of(new McpSchema.PromptArgument(p.argument(), p.argDescription(), true)));
            out.add(new SyncPromptSpecification(prompt, (ex, req) -> {
                Object v = req.arguments() == null ? null : req.arguments().get(p.argument());
                if (v == null || v.toString().isBlank()) throw new IllegalArgumentException(p.argument() + " is required");
                // The argument becomes a name in a sentence, so it stays one word.
                String safe = v.toString().replaceAll("[^A-Za-z0-9@#$_-]", "");
                return new McpSchema.GetPromptResult(p.description(), List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(p.text().formatted(safe)))));
            }));
        }
        return out;
    }

    @Bean
    public List<SyncResourceTemplateSpecification> toolkitResources(LegacyTools legacy, FlowTools flow, ToolRuntime runtime) {
        ToolDef getProgram = find(legacy.tools(), "get_program"), getCopybook = find(legacy.tools(), "get_copybook"), jobFlow = find(flow.tools(), "job_flow");
        return List.of(
                template("legacy://program/{name}", "program", "A program bundle", runtime, getProgram, "name"),
                template("legacy://copybook/{name}", "copybook", "A record layout", runtime, getCopybook, "name"),
                template("legacy://job/{name}", "job", "A job's steps and datasets", runtime, jobFlow, "job"));
    }

    private static SyncResourceTemplateSpecification template(String uri, String name, String description, ToolRuntime runtime, ToolDef tool, String argument) {
        var t = new McpSchema.ResourceTemplate(uri, name, description, "application/json", null);
        return new SyncResourceTemplateSpecification(t, (ex, req) -> {
            String value = req.uri().substring(req.uri().lastIndexOf('/') + 1);
            Identity who = ToolkitMcpConfig.identityOf(ex);
            ToolRuntime.Outcome o;
            try {
                o = runtime.call(tool, JSON.writeValueAsString(Map.of(argument, value)), who);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(req.uri(), "application/json", o.json())));
        });
    }

    private static ToolDef find(List<ToolDef> all, String name) {
        return all.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }
}
