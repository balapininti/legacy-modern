package com.modtoolkit.cli;

import com.modtoolkit.retrieval.ChunkIndexer;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;

@Command(name = "index", description = "Build the lexical index over code chunks; only changed members are redone.")
public class IndexCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
            System.out.println(ctx.getBean(ChunkIndexer.class).index());
        }
        return 0;
    }
}
