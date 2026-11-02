package com.modtoolkit.knowledge;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.modtoolkit.core.config.ToolkitConfig;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** The endpoint named in config is the only place source ever goes. Temperature 0: the same input gets the same answer. */
public final class LlmFactory {
    private LlmFactory() {}

    public static LlmClient chat(ToolkitConfig.Llm c, Function<String, String> env) {
        if (c == null || c.provider() == null) throw new IllegalStateException("toolkit.yaml has no llm block");
        String key = c.apiKeyEnv() == null ? null : env.apply(c.apiKeyEnv());
        return new SpringAiLlmClient(model(c, key), c.chatModel());
    }

    private static ChatModel model(ToolkitConfig.Llm c, String key) {
        return switch (c.provider()) {
            case "openai", "openai-compatible" -> {
                OpenAiApi.Builder api = OpenAiApi.builder().apiKey(key == null || key.isBlank() ? "none" : key).restClientBuilder(slow());
                if (c.endpoint() != null && !c.endpoint().isBlank()) api.baseUrl(c.endpoint());
                yield OpenAiChatModel.builder().openAiApi(api.build()).defaultOptions(OpenAiChatOptions.builder().model(c.chatModel()).temperature(0.0).build()).build();
            }
            case "anthropic" -> {
                AnthropicApi.Builder api = AnthropicApi.builder().apiKey(key).restClientBuilder(slow());
                if (c.endpoint() != null && !c.endpoint().isBlank()) api.baseUrl(c.endpoint());
                yield AnthropicChatModel.builder().anthropicApi(api.build()).defaultOptions(AnthropicChatOptions.builder().model(c.chatModel()).maxTokens(8192).temperature(0.0).build()).build();
            }
            case "azure-openai" -> AzureOpenAiChatModel.builder()
                    .openAIClientBuilder(new OpenAIClientBuilder().endpoint(c.endpoint()).credential(new AzureKeyCredential(key)))
                    .defaultOptions(AzureOpenAiChatOptions.builder().deploymentName(c.chatModel()).temperature(0.0).build()).build();
            case "bedrock" -> BedrockProxyChatModel.builder().credentialsProvider(DefaultCredentialsProvider.create())
                    .region(Region.of(c.region() == null ? "us-east-1" : c.region()))
                    .defaultOptions(BedrockChatOptions.builder().model(c.chatModel()).temperature(0.0).build()).build();
            default -> throw new IllegalStateException("unknown provider " + c.provider());
        };
    }

    /** A local model on CPUs can take many minutes over one large program. */
    private static RestClient.Builder slow() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(30));
        f.setReadTimeout(Duration.ofMinutes(30));
        return RestClient.builder().requestFactory(f);
    }
}
