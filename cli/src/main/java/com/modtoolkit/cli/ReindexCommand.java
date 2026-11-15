package com.modtoolkit.cli;

import com.modtoolkit.core.VectorSupport;
import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.knowledge.EmbedderFactory;
import com.modtoolkit.retrieval.Embedder;
import com.modtoolkit.retrieval.EmbeddingIndexer;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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
            // pgvector may have been installed since the database was set up: this is when the column is added.
            if (!VectorSupport.enable(ctx.getBean(JdbcTemplate.class))) {
                System.err.println("pgvector is not available on this PostgreSQL, so there is nothing to reindex and search stays lexical-only."
                        + " To turn vector search on, install pgvector 0.8 for PostgreSQL 17 on the database server (and let this role create it, or have the DBA run CREATE EXTENSION vector), then run toolkit reindex again");
                return 1;
            }
            EmbeddingIndexer idx = ctx.getBean(EmbeddingIndexer.class);
            idx.clear();
            System.out.println(idx.embedPending(e));
        }
        return 0;
    }
}
