package com.modtoolkit.knowledge;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.retrieval.Embedder;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingModel;
import org.springframework.ai.azure.openai.AzureOpenAiEmbeddingOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** Spring AI has embedding models for OpenAI-compatible endpoints and Azure; Anthropic offers none. */
public final class EmbedderFactory {
    private EmbedderFactory() {}

    public static Embedder create(ToolkitConfig.Llm c, Function<String, String> env) {
        if (c == null || c.embeddingModel() == null || c.embeddingModel().isBlank()) return null;
        String key = c.apiKeyEnv() == null ? null : env.apply(c.apiKeyEnv());
        EmbeddingModel m = switch (c.provider()) {
            case "openai", "openai-compatible" -> {
                OpenAiApi.Builder api = OpenAiApi.builder().apiKey(key == null || key.isBlank() ? "none" : key);
                if (c.endpoint() != null && !c.endpoint().isBlank()) api.baseUrl(c.endpoint());
                yield new OpenAiEmbeddingModel(api.build(), MetadataMode.NONE, OpenAiEmbeddingOptions.builder().model(c.embeddingModel()).build());
            }
            case "azure-openai" -> new AzureOpenAiEmbeddingModel(new OpenAIClientBuilder().endpoint(c.endpoint()).credential(new AzureKeyCredential(key)).buildClient(),
                    MetadataMode.NONE, AzureOpenAiEmbeddingOptions.builder().deploymentName(c.embeddingModel()).build());
            default -> throw new IllegalStateException("provider " + c.provider() + " has no embedding model here; use openai-compatible or azure-openai for embeddings");
        };
        int dim = c.embeddingDim() == null ? 1536 : c.embeddingDim();
        String model = c.embeddingModel();
        return new Embedder() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return m.embed(texts);
            }

            @Override
            public String model() {
                return model;
            }

            private boolean nomic() {
                return model.toLowerCase(java.util.Locale.ROOT).contains("nomic");
            }

            @Override
            public String asDocument(String text) {
                return nomic() ? "search_document: " + text : text;
            }

            @Override
            public String asQuery(String text) {
                return nomic() ? "search_query: " + text : text;
            }

            @Override
            public int dimension() {
                return dim;
            }
        };
    }
}
