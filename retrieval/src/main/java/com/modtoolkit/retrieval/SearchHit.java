package com.modtoolkit.retrieval;

/** Every hit names where it came from. artifactId and status are null for raw code. */
public record SearchHit(String kind, String member, Integer lineStart, Integer lineEnd, String snippet, double score, Long artifactId, String status, String unit) {}
