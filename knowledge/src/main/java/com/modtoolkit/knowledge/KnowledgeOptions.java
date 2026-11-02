package com.modtoolkit.knowledge;

import java.util.List;
import java.util.Set;

/**
 * @param budgetUsd stop before spending more than this; null for no limit
 * @param budgetTokens stop before using more than this; null for no limit
 * @param programs limit to these programs; empty for all
 * @param kinds limit to these artifact kinds; empty for all
 */
public record KnowledgeOptions(Double budgetUsd, Long budgetTokens, double inputUsdPerMtok, double outputUsdPerMtok, int concurrency, int maxContextTokens, List<String> programs, Set<String> kinds, boolean redactPii, List<String> piiPatterns) {
    public static KnowledgeOptions defaults() {
        return new KnowledgeOptions(null, null, 0, 0, 2, 60_000, List.of(), Set.of(), false, List.of());
    }

    public KnowledgeOptions withBudgetTokens(long t) {
        return new KnowledgeOptions(budgetUsd, t, inputUsdPerMtok, outputUsdPerMtok, concurrency, maxContextTokens, programs, kinds, redactPii, piiPatterns);
    }

    public KnowledgeOptions withScope(List<String> p, Set<String> k) {
        return new KnowledgeOptions(budgetUsd, budgetTokens, inputUsdPerMtok, outputUsdPerMtok, concurrency, maxContextTokens, p, k, redactPii, piiPatterns);
    }

    public KnowledgeOptions withContext(int tokens) {
        return new KnowledgeOptions(budgetUsd, budgetTokens, inputUsdPerMtok, outputUsdPerMtok, concurrency, tokens, programs, kinds, redactPii, piiPatterns);
    }

    public KnowledgeOptions withRedaction(List<String> patterns) {
        return new KnowledgeOptions(budgetUsd, budgetTokens, inputUsdPerMtok, outputUsdPerMtok, concurrency, maxContextTokens, programs, kinds, true, patterns);
    }

    public KnowledgeOptions withConcurrency(int c) {
        return new KnowledgeOptions(budgetUsd, budgetTokens, inputUsdPerMtok, outputUsdPerMtok, c, maxContextTokens, programs, kinds, redactPii, piiPatterns);
    }
}
