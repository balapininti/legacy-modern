package com.modtoolkit.parsers.cobol;

/** One EXEC ... END-EXEC. Lines are 1-based in the expanded source; text is the code with line breaks as spaces. */
public record ExecBlock(String kind, int startLine, int endLine, String text) {}
