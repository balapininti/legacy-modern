package com.modtoolkit.cli;

import com.modtoolkit.discovery.SyntheticEstate;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "synth", description = "Generate a synthetic estate for performance measurement.")
public class SynthCommand implements Callable<Integer> {
    @Option(names = "--out", required = true) Path out;
    @Option(names = "--lines", defaultValue = "1000000") long lines;
    @Option(names = "--seed", defaultValue = "42") long seed;

    @Override
    public Integer call() throws Exception {
        System.out.println(SyntheticEstate.generate(out, lines, seed));
        return 0;
    }
}
