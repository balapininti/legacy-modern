package com.modtoolkit.core.config;

/** One validation problem; {@code path} uses the yaml key names so a reader can find it. */
public record ConfigIssue(String path, String message) {}
