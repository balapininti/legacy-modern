package com.modtoolkit.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validates every block without touching the network; {@link Doctor} does the live checks. */
public final class ConfigValidator {
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    private ConfigValidator() {}

    public static List<ConfigIssue> validate(ToolkitConfig c) {
        List<ConfigIssue> out = new ArrayList<>();
        required(out, "client", c.client());
        source(out, c.source());
        llm(out, c.llm());
        mcp(out, c.mcp());
        zos(out, c.zos());
        target(out, c.target());
        if (c.ui() != null) {
            env(out, "ui.oidc_client_id_env", c.ui().oidcClientIdEnv());
        }
        return out;
    }

    private static void source(List<ConfigIssue> out, ToolkitConfig.Source s) {
        if (s == null) {
            out.add(new ConfigIssue("source", "block is required"));
            return;
        }
        required(out, "source.root", s.root());
        oneOf(out, "source.refresh", s.refresh(), "git", "manual");
        oneOf(out, "source.encoding", s.encoding(), "auto", "cp037", "cp1047", "utf-8");
        oneOf(out, "source.format", s.format(), "auto", "fixed", "free");
        oneOf(out, "source.dialect", s.dialect(), "ibm-enterprise", "microfocus");
    }

    private static void llm(List<ConfigIssue> out, ToolkitConfig.Llm l) {
        if (l == null) {
            return; // Discovery and MCP v1 need no LLM.
        }
        oneOf(out, "llm.provider", l.provider(), "azure-openai", "bedrock", "openai", "anthropic", "openai-compatible");
        env(out, "llm.api_key_env", l.apiKeyEnv());
        if (l.concurrency() != null && l.concurrency() < 1) {
            out.add(new ConfigIssue("llm.concurrency", "must be at least 1"));
        }
        if (l.budgetUsdPerRun() != null && l.budgetUsdPerRun() < 0) {
            out.add(new ConfigIssue("llm.budget_usd_per_run", "must not be negative"));
        }
        if (l.embeddingDim() != null && l.embeddingDim() < 1) {
            out.add(new ConfigIssue("llm.embedding_dim", "must be at least 1"));
        }
        if (l.piiPatterns() != null) {
            for (int i = 0; i < l.piiPatterns().size(); i++) {
                try {
                    Pattern.compile(l.piiPatterns().get(i));
                } catch (PatternSyntaxException e) {
                    out.add(new ConfigIssue("llm.pii_patterns[" + i + "]", "invalid regex: " + e.getDescription()));
                }
            }
        }
    }

    private static void mcp(List<ConfigIssue> out, ToolkitConfig.Mcp m) {
        if (m == null) {
            return;
        }
        oneOf(out, "mcp.transport", m.transport(), "http", "stdio");
        oneOf(out, "mcp.auth", m.auth(), "oidc", "token");
    }

    private static void zos(List<ConfigIssue> out, ToolkitConfig.Zos z) {
        if (z == null) {
            return;
        }
        oneOf(out, "zos.method", z.method(), "zowe", "ftp-jes", "none");
        env(out, "zos.user_env", z.userEnv());
        env(out, "zos.password_env", z.passwordEnv());
        if (Boolean.TRUE.equals(z.enabled()) && !"none".equals(z.method())) {
            required(out, "zos.host", z.host());
            required(out, "zos.job_card", z.jobCard());
            required(out, "zos.work_hlq", z.workHlq());
        }
    }

    private static void target(List<ConfigIssue> out, ToolkitConfig.Target t) {
        if (t == null) {
            return;
        }
        oneOf(out, "target.build", t.build(), "maven", "gradle");
        oneOf(out, "target.database", t.database(), "postgresql", "oracle", "sqlserver");
        if (Boolean.TRUE.equals(t.indexExisting())) {
            required(out, "target.local_path", t.localPath());
        }
    }

    private static void required(List<ConfigIssue> out, String path, String v) {
        if (v == null || v.isBlank()) {
            out.add(new ConfigIssue(path, "is required"));
        }
    }

    private static void oneOf(List<ConfigIssue> out, String path, String v, String... allowed) {
        if (v != null && !Set.of(allowed).contains(v)) {
            out.add(new ConfigIssue(path, "'" + v + "' is not one of " + String.join(" | ", allowed)));
        }
    }

    private static void env(List<ConfigIssue> out, String path, String v) {
        if (v != null && !ENV_NAME.matcher(v).matches()) {
            // Never echo the value: it may be a pasted secret.
            out.add(new ConfigIssue(path, "must name an environment variable (UPPER_SNAKE), not hold a secret"));
        }
    }
}
