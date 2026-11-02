package com.modtoolkit.cli;

import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.knowledge.EmbedderFactory;
import com.modtoolkit.retrieval.Embedder;
import com.modtoolkit.retrieval.EmbeddingIndexer;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "reindex", description = "Re-embed everything, as when the embedding model changes.")
public class ReindexCommand implements Callable<Integer> {
    @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;

    @Override
    public Integer call() {
        Embedder e = EmbedderFactory.create(ToolkitConfig.load(config).llm(), System::getenv);
        if (e == null) {
            System.err.println(config + " names no embedding model");
            return 2;
        }
        try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
            EmbeddingIndexer idx = ctx.getBean(EmbeddingIndexer.class);
            idx.clear();
            System.out.println(idx.embedPending(e));
        }
        return 0;
    }
}
