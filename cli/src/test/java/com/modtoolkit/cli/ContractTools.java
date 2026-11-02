package com.modtoolkit.cli;

import java.util.Set;

/** The contract so far; grows with each milestone and then stays frozen. */
final class ContractTools {
    static final Set<String> EXPECTED = Set.of("inventory_summary", "search", "get_program", "get_source", "get_copybook", "who_calls", "calls_from", "table_usage", "job_flow", "screen_flow", "trace_field", "get_modern_impl", "gap_report", "deviations", "record_deviation", "business_rules");

    private ContractTools() {}
}
