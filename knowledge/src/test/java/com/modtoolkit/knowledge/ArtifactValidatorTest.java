package com.modtoolkit.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modtoolkit.knowledge.ArtifactValidator.Facts;
import com.modtoolkit.knowledge.ArtifactValidator.Invalid;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArtifactValidatorTest {
    private static final Facts F = new Facts(10, 100, Set.of("P100"), Set.of("WS-A", "WS-B"));

    private static String rule(int s, int e, String para, String items) {
        return "{\"rules\":[{\"rule_id\":\"X-R1\",\"statement\":\"a rule here\",\"condition\":\"c\",\"action\":\"a\",\"paragraph\":\"" + para + "\",\"line_start\":" + s + ",\"line_end\":" + e + ",\"data_items\":[" + items + "],\"confidence\":0.9}]}";
    }

    @Test
    void acceptsAValidRuleListAndFencedJson() {
        assertThat(ArtifactValidator.validate("rule", rule(20, 30, "P100", "\"WS-A\""), F).get("rules")).hasSize(1);
        assertThat(ArtifactValidator.validate("rule", "```json\n" + rule(20, 30, "P100", "\"WS-A\"") + "\n```", F).get("rules")).hasSize(1);
    }

    @Test
    void rejectsFreeTextAndSchemaViolations() {
        assertThatThrownBy(() -> ArtifactValidator.validate("rule", "Here are the rules: none.", F)).isInstanceOf(Invalid.class).hasMessageContaining("not JSON");
        assertThatThrownBy(() -> ArtifactValidator.validate("rule", "[]", F)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> ArtifactValidator.validate("rule", "{\"rules\":[{\"rule_id\":\"X\"}]}", F)).isInstanceOf(Invalid.class).hasMessageContaining("schema");
    }

    @Test
    void rejectsALineRangeThatDoesNotExist() {
        assertThatThrownBy(() -> ArtifactValidator.validate("rule", rule(5, 30, "P100", ""), F)).isInstanceOf(Invalid.class).hasMessageContaining("outside");
        assertThatThrownBy(() -> ArtifactValidator.validate("rule", rule(90, 101, "P100", ""), F)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> ArtifactValidator.validate("rule", rule(30, 20, "P100", ""), F)).isInstanceOf(Invalid.class).hasMessageContaining("after");
    }

    @Test
    void rejectsAParagraphTheAstDoesNotHave() {
        assertThatThrownBy(() -> ArtifactValidator.validate("rule", rule(20, 30, "NOPE", ""), F)).isInstanceOf(Invalid.class).hasMessageContaining("NOPE");
    }

    @Test
    void movesAsideDataItemsTheAstDoesNotKnow() {
        var o = ArtifactValidator.validate("rule", rule(20, 30, "P100", "\"WS-A\",\"WS-GHOST\""), F);
        assertThat(o.get("rules").get(0).get("data_items").toString()).isEqualTo("[\"WS-A\"]");
        assertThat(o.get("rules").get(0).get("unverified_data_items").toString()).contains("WS-GHOST");
    }

    @Test
    void dictionaryLineAndNameAreChecked() {
        String good = "{\"entries\":[{\"data_item\":\"WS-A\",\"meaning\":\"m\",\"domain_values\":[],\"pii\":false,\"line\":20}]}";
        assertThat(ArtifactValidator.validate("dictionary", good, F).get("entries")).hasSize(1);
        assertThatThrownBy(() -> ArtifactValidator.validate("dictionary", good.replace("WS-A", "WS-Z"), F)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> ArtifactValidator.validate("dictionary", good.replace("20", "999"), F)).isInstanceOf(Invalid.class);
    }

    @Test
    void piiRedactionKeepsLengthAndLines() {
        PiiRedactor r = new PiiRedactor(true, java.util.List.of("\\b\\d{3}-\\d{2}-\\d{4}\\b"));
        assertThat(r.redact("12: MOVE '123-45-6789' TO WS-SSN\n13: X")).isEqualTo("12: MOVE '###########' TO WS-SSN\n13: X");
        assertThat(new PiiRedactor(false, java.util.List.of("\\d")).redact("123")).isEqualTo("123");
    }
}
