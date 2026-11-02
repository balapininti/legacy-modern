package com.modtoolkit.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolkitConfigTest {
    @TempDir Path dir;

    private static Path sample() {
        return Path.of("src/test/resources/config/valid-toolkit.yaml");
    }

    private ToolkitConfig withEdit(String from, String to) throws Exception {
        String y = Files.readString(sample());
        assertThat(y).contains(from);
        Path p = dir.resolve("t.yaml");
        Files.writeString(p, y.replace(from, to));
        return ToolkitConfig.load(p);
    }

    @Test
    void specSampleLoadsAndValidates() throws Exception {
        ToolkitConfig c = ToolkitConfig.load(sample());
        assertThat(c.client()).isEqualTo("acme-insurance");
        assertThat(c.llm().embeddingDim()).isEqualTo(3072);
        assertThat(c.llm().piiPatterns()).containsExactly("\\b\\d{3}-\\d{2}-\\d{4}\\b");
        assertThat(c.scope().phase1Programs()).containsExactly("CBACT01C", "CBTRN02C");
        assertThat(c.target().packageRoot()).isEqualTo("com.acme.policy");
        assertThat(ConfigValidator.validate(c)).isEmpty();
    }

    @Test
    void missingSourceRootIsOneIssue() throws Exception {
        ToolkitConfig c = withEdit("root: /data/source", "root: \"\"");
        assertThat(ConfigValidator.validate(c)).extracting(ConfigIssue::path).containsExactly("source.root");
    }

    @Test
    void badEnumIsOneIssue() throws Exception {
        ToolkitConfig c = withEdit("provider: azure-openai", "provider: gemini");
        List<ConfigIssue> issues = ConfigValidator.validate(c);
        assertThat(issues).extracting(ConfigIssue::path).containsExactly("llm.provider");
        assertThat(issues.get(0).message()).contains("openai-compatible");
    }

    @Test
    void inlineSecretIsRejected() throws Exception {
        ToolkitConfig c = withEdit("api_key_env: TOOLKIT_LLM_KEY", "api_key_env: sk-abc123secretvalue");
        assertThat(ConfigValidator.validate(c)).extracting(ConfigIssue::path).containsExactly("llm.api_key_env");
    }

    @Test
    void unknownKeyFailsLoadNamingTheKey() {
        assertThatThrownBy(() -> withEdit("client: acme-insurance", "client: a\nclinet: b"))
                .isInstanceOf(ConfigLoadException.class)
                .hasMessageContaining("clinet");
    }

    @Test
    void badPiiRegexIsAnIssue() throws Exception {
        ToolkitConfig c = withEdit("pii_patterns: [\"\\\\b\\\\d{3}-\\\\d{2}-\\\\d{4}\\\\b\"]", "pii_patterns: [\"(unclosed\"]");
        assertThat(ConfigValidator.validate(c)).extracting(ConfigIssue::path).containsExactly("llm.pii_patterns[0]");
    }

    @Test
    void zosDisabledDoesNotRequireHost() throws Exception {
        String y = Files.readString(sample()).replace("enabled: true", "enabled: false").replace("host: testlpar.acme.internal", "host: \"\"");
        Path p = dir.resolve("z.yaml");
        Files.writeString(p, y);
        assertThat(ConfigValidator.validate(ToolkitConfig.load(p))).isEmpty();
    }
}
