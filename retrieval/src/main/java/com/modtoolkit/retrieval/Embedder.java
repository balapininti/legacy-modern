package com.modtoolkit.retrieval;

import java.util.List;

/** Turns text into vectors through the endpoint the client approved. */
public interface Embedder {
    List<float[]> embed(List<String> texts);

    String model();

    int dimension();
}
