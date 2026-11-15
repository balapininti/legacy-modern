package com.modtoolkit.cli;

import com.modtoolkit.cli.launch.Home;
import com.modtoolkit.cli.launch.Launcher;
import com.modtoolkit.cli.launch.SetupCommand;
import com.modtoolkit.cli.launch.StartCommand;
import com.modtoolkit.cli.launch.StopCommand;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.OptionSpec;

@Command(name = "toolkit", mixinStandardHelpOptions = true, version = "toolkit 0.1.0",
        description = "Mainframe Modernization Toolkit",
        subcommands = {DoctorCommand.class, DiscoverCommand.class, SynthCommand.class, IndexCommand.class, McpCommand.class, TokenCommand.class, ServeCommand.class, KnowledgeCommand.class, ReindexCommand.class, EvalCommand.class, RulebookCommand.class, InitCommand.class, StatusCommand.class, CaptureCommand.class, TestsCommand.class,
                SetupCommand.class, StartCommand.class, StopCommand.class})
public class ToolkitMain {
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** In an install (TOOLKIT_HOME), ordinary commands run in a child JVM with the install's environment. */
    static int run(String[] args) {
        Map<String, String> env = System.getenv();
        Optional<Home> home = Home.detect();
        if (Launcher.delegates(home, args, env)) {
            return Launcher.delegate(home.get(), args);
        }
        CommandLine cl = new CommandLine(new ToolkitMain());
        configDefaults(cl, env);
        return cl.execute(args);
    }

    /** In the launcher's child, {@code -c/--config} defaults to the install's TOOLKIT_CONFIG instead of ./toolkit.yaml. */
    public static void configDefaults(CommandLine cl, Map<String, String> env) {
        String cfg = env.get("TOOLKIT_CONFIG");
        if (!env.containsKey(Launcher.CHILD_MARKER) || cfg == null || cfg.isBlank()) return;
        cl.setDefaultValueProvider(arg -> arg instanceof OptionSpec o && "--config".equals(o.longestName()) ? cfg : null);
    }
}
