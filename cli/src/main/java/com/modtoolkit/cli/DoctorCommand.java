package com.modtoolkit.cli;

import com.modtoolkit.core.config.Doctor;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "doctor", description = "Check source, database, LLM, z/OS and identity provider.")
public class DoctorCommand implements Callable<Integer> {
    @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml", description = "Path to toolkit.yaml")
    Path config;

    @Override
    public Integer call() {
        List<Doctor.CheckResult> results = Doctor.fromEnvironment().runChecked(config);
        for (Doctor.CheckResult r : results) {
            System.out.printf("%-4s  %-22s %s%n", r.status(), r.check(), r.detail());
        }
        return Doctor.ok(results) ? 0 : 1;
    }
}
