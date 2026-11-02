package com.modtoolkit.retrieval;

import java.util.List;

/** Turns text into vectors through the endpoint the client approved. */
public interface Embedder {
    List<float[]> embed(List<String> texts);

    String model();

    /** Text as this model wants a document embedded; some models need a task prefix. */
    default String asDocument(String text) {
        return text;
    }

    /** Text as this model wants a query embedded. */
    default String asQuery(String text) {
        return text;
    }

    int dimension();
}
