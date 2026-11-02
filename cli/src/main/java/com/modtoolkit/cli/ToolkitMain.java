package com.modtoolkit.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "toolkit", mixinStandardHelpOptions = true, version = "toolkit 0.1.0",
        description = "Mainframe Modernization Toolkit",
        subcommands = {DoctorCommand.class, DiscoverCommand.class, SynthCommand.class, IndexCommand.class, McpCommand.class, TokenCommand.class})
public class ToolkitMain {
    public static void main(String[] args) {
        System.exit(new CommandLine(new ToolkitMain()).execute(args));
    }
}
