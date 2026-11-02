package com.modtoolkit.cli;

import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.knowledge.EmbedderFactory;
import com.modtoolkit.retrieval.ArtifactChunks;
import com.modtoolkit.retrieval.ChunkIndexer;
import com.modtoolkit.retrieval.Embedder;
import com.modtoolkit.retrieval.EmbeddingIndexer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "index", description = "Build the lexical and vector indexes; only what changed is redone.")
public class IndexCommand implements Callable<Integer> {
    @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;

    @Override
    public Integer call() throws Exception {
        try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
            System.out.println(ctx.getBean(ChunkIndexer.class).index());
            int artifacts = ctx.getBean(ArtifactChunks.class).index();
            if (artifacts > 0) System.out.println("artifact chunks: " + artifacts);
            if (Files.exists(config)) {
                Embedder e = EmbedderFactory.create(ToolkitConfig.load(config).llm(), System::getenv);
                if (e != null) System.out.println(ctx.getBean(EmbeddingIndexer.class).embedPending(e));
            }
        }
        return 0;
    }
}
