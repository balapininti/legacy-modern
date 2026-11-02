package com.modtoolkit.parsers.cobol;

public class CobolParseException extends RuntimeException {
    private final Loc at;

    public CobolParseException(String message, Loc at) {
        super(message);
        this.at = at;
    }

    public Loc at() {
        return at;
    }
}
