package com.modtoolkit.mcp;

/** The only way a tool reports failure: code, message and hint, never a stack trace. */
public class ToolError extends RuntimeException {
    private final String code;
    private final String hint;

    public ToolError(String code, String message, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}
