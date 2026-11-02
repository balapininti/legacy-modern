package com.modtoolkit.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Everything client-specific. Secrets are referenced by environment variable name, never stored. */
public record ToolkitConfig(
        String client, Source source, Llm llm, Mcp mcp, Zos zos, Target target, Scope scope, Ui ui) {

    public record Source(
            String root, String refresh, String encoding, String format, String dialect,
            List<String> include, List<String> exclude) {}

    public record Llm(
            String provider, String endpoint, String apiKeyEnv, String chatModel, String embeddingModel,
            Integer embeddingDim, Double budgetUsdPerRun, Integer concurrency, Boolean redactPii,
            List<String> piiPatterns, Long budgetTokensPerRun, Double inputUsdPerMtok, Double outputUsdPerMtok, Integer maxContextTokens, String region) {}

    public record Mcp(String transport, String baseUrl, String auth) {}

    public record Zos(
            Boolean enabled, String method, String host, String userEnv, String passwordEnv,
            String jobCard, String workHlq) {}

    public record Target(
            Integer java, String springBoot, Integer angular, String build, String database, String auth,
            String repo, String localPath, Boolean indexExisting, String packageRoot) {}

    public record Scope(List<String> phase1Programs, List<String> phase1Jobs) {}

    public record Ui(String oidcIssuer, String oidcClientIdEnv) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static ToolkitConfig load(Path path) {
        try {
            ToolkitConfig c = YAML.readValue(path.toFile(), ToolkitConfig.class);
            if (c == null) {
                throw new ConfigLoadException(path + " is empty");
            }
            return c;
        } catch (UnrecognizedPropertyException e) {
            throw new ConfigLoadException(
                    "unknown key '" + e.getPropertyName() + "' at line " + e.getLocation().getLineNr() + " of " + path, e);
        } catch (IOException e) {
            throw new ConfigLoadException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }
}
