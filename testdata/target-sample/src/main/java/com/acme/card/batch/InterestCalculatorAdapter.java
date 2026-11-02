package com.acme.card.batch;

import com.fasterxml.jackson.databind.JsonNode;

/** Binds the toolkit's fixture format to the code under test. */
public class InterestCalculatorAdapter {
    public JsonNode run(JsonNode input) {
        return new InterestCalculator().run(input.get("parm").asText(), input.get("datasets"));
    }
}
