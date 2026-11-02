package com.modtoolkit.parsers.cobol;

/** Provenance: a member name and a 1-based normalized line in that member. */
public record Loc(String member, int line) {}
