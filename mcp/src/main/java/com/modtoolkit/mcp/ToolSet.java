package com.modtoolkit.mcp;

import java.util.List;

/** A group of tools; the server exposes the union. */
@FunctionalInterface
public interface ToolSet {
    List<ToolDef> tools();
}
