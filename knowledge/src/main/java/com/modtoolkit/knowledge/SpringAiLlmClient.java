package com.modtoolkit.knowledge;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;

final class SpringAiLlmClient implements LlmClient {
    private final ChatModel chat;
    private final String model;

    SpringAiLlmClient(ChatModel chat, String model) {
        this.chat = chat;
        this.model = model;
    }

    @Override
    public Response complete(String unitKey, String system, String user) {
        try {
            ChatResponse r = chat.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            var usage = r.getMetadata() == null ? null : r.getMetadata().getUsage();
            String text = r.getResult() == null ? "" : r.getResult().getOutput().getText();
            return new Response(text == null ? "" : text, usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(), usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
        } catch (org.springframework.ai.retry.TransientAiException | org.springframework.web.client.ResourceAccessException e) {
            throw new TransientFailure(e.getMessage(), e);
        }
    }

    @Override
    public String model() {
        return model;
    }
}
