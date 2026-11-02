package com.modtoolkit.parsers.normalize;

/** Values from the source block of toolkit.yaml. "auto" asks for detection. */
public record SourceOptions(String encoding, String format) {
    public static final SourceOptions AUTO = new SourceOptions("auto", "auto");
}
