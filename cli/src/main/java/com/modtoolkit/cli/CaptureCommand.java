package com.modtoolkit.cli;

import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.verify.CaptureClient;
import com.modtoolkit.verify.CaptureRunner;
import com.modtoolkit.verify.FixtureService;
import com.modtoolkit.verify.FtpJesCapture;
import com.modtoolkit.verify.ZoweCapture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "capture", description = "Capture behavior for characterization tests.", subcommands = {CaptureCommand.Submit.class, CaptureCommand.Import.class})
public class CaptureCommand {
    @Command(name = "import", description = "Decode exported datasets into fixtures; needs no LPAR. See the manifest format in INSTALL.md.")
    static class Import implements Callable<Integer> {
        @Option(names = "--dir", required = true) Path dir;
        @Option(names = "--encoding", defaultValue = "cp037", description = "cp037, cp1047 or utf-8") String encoding;

        @Override
        public Integer call() throws Exception {
            try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
                System.out.println(ctx.getBean(FixtureService.class).importCapture(dir, encoding));
            }
            return 0;
        }
    }

    @Command(name = "submit", description = "Run a copy of a job on the test LPAR and download its datasets.")
    static class Submit implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;
        @Option(names = "--program", required = true) String program;
        @Option(names = "--jcl", required = true, description = "A file holding the job's steps; the job card comes from config") Path jcl;
        @Option(names = "--out", required = true, description = "Directory for the capture") Path out;
        @Option(names = "--dataset", required = true, split = ",", description = "Output datasets to download") List<String> datasets;
        @Option(names = "--timeout-minutes", defaultValue = "30") int minutes;

        @Override
        public Integer call() throws Exception {
            ToolkitConfig.Zos z = ToolkitConfig.load(config).zos();
            if (z == null || !Boolean.TRUE.equals(z.enabled()) || "none".equals(z.method())) {
                System.err.println("zos is disabled in " + config + ": import exported datasets instead");
                return 2;
            }
            String user = System.getenv(z.userEnv()), pass = System.getenv(z.passwordEnv());
            CaptureClient c = "ftp-jes".equals(z.method()) ? new FtpJesCapture(z.host(), 21, user, pass) : new ZoweCapture(System.getenv().getOrDefault("TOOLKIT_ZOWE", "zowe"), z.host(), user, pass);
            CaptureRunner.Result r = new CaptureRunner(c).run(z.jobCard(), Files.readString(jcl), datasets, out, Duration.ofMinutes(minutes));
            System.out.println(r);
            return r.returnCode().contains("0000") ? 0 : 1;
        }
    }
}
