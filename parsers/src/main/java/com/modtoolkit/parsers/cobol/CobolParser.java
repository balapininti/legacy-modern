package com.modtoolkit.parsers.cobol;

/** One interface for every COBOL front end. */
public interface CobolParser {
    String name();

    /** @throws CobolParseException when the member is rejected, with the error position in the expanded source */
    CobolFacts parse(ExpandedSource src);
}
