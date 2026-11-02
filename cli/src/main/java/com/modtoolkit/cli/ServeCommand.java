package com.modtoolkit.cli;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;

@Command(name = "serve", description = "Run the REST API behind the web UI.")
public class ServeCommand implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        ToolkitApp.api();
        new CountDownLatch(1).await();
        return 0;
    }
}
