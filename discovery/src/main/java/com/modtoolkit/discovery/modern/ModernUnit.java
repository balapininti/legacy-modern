package com.modtoolkit.discovery.modern;

import java.util.Map;

/** One indexed element of the target repository, as in the modern_unit table. */
public record ModernUnit(String kind, String name, String path, String signature, int lineStart, int lineEnd, String contentHash, Map<String, Object> attrs) {}
