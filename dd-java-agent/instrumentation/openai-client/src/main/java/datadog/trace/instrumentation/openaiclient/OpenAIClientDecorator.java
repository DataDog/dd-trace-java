package datadog.trace.instrumentation.openaiclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import com.openai.models.chat.completions.*;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionChoice;
import com.openai.models.completions.CompletionCreateParams;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.List;
import java.util.Optional;

public class OpenAIClientDecorator extends ClientDecorator {
  private static final String mlApp = Config.get().getLlmObsMlApp();
  private static final String mlProvider = "openai";
  private static final String COMPONENT_NAME = "openai";
  private static final UTF8BytesString OPENAI_REQUEST = UTF8BytesString.create("openai.request");

  public static final OpenAIClientDecorator DECORATE = new OpenAIClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"openai"};
  }

  @Override
  protected CharSequence component() {
    return UTF8BytesString.create(COMPONENT_NAME);
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_CLIENT;
  }

  @Override
  protected String service() {
    return null;
  }

  public AgentScope startChatCompletionSpan(ChatCompletionCreateParams params) {
    AgentSpan span = startSpan(OPENAI_REQUEST);
    span.setTag("openai.request.endpoint", "/chat/completions");
    span.setResourceName("chat.completions.create");
    span.setTag("openai.request.provider", "openai");
    extractChatCompletionRequestData(span, params);
    afterStart(span);
    return activateSpan(span);
  }

  public AgentScope startLLMChatCompletionSpan(ChatCompletionCreateParams params) {
    AgentSpan span = startSpan(OPENAI_REQUEST);
    span.setTag("openai.request.endpoint", "/chat/completions");
    span.setResourceName("chat.completions.create");
    span.setTag("openai.request.provider", "openai");
    extractChatCompletionRequestData(span, params);
    afterStart(span);
    return activateSpan(span);
  }

  public AgentScope startCompletionSpan(CompletionCreateParams params) {
    AgentSpan span = startSpan(OPENAI_REQUEST);
    span.setTag(Tags.COMPONENT, COMPONENT_NAME);
    span.setTag("openai.request.endpoint", "/completions");
    span.setResourceName("completions.create");
    span.setTag("ai.provider", "openai");

    extractCompletionRequestData(span, params);
    afterStart(span);
    return activateSpan(span);
  }

  public AgentScope startEmbeddingSpan(EmbeddingCreateParams params) {
    AgentSpan span = startSpan(OPENAI_REQUEST);
    span.setTag("openai.request.endpoint", "/embeddings");
    span.setResourceName("embeddings.create");
    span.setTag("ai.provider", "openai");

    extractEmbeddingRequestData(span, params);
    afterStart(span);
    return activateSpan(span);
  }

  public LLMObsSpan startLLMObsChatCompletionSpan(ChatCompletionCreateParams params) {
    String modelName = params.model().toString();

    LLMObsSpan llmObsSpan =
        LLMObs.startLLMSpan("chat_completion", modelName, mlProvider, mlApp, null);

    // Extract and set input data from chat completion params
    // May need to be reformatted
    StringBuilder inputData = new StringBuilder();
    List<ChatCompletionMessageParam> messages = params.messages();
    for (int i = 0; i < messages.size(); i++) {
      ChatCompletionMessageParam messageParam = messages.get(i);
      if (i > 0) {
        inputData.append(" | ");
      }

      if (messageParam.isUser()) {
        inputData.append("User: ").append(messageParam.asUser().content());
      } else if (messageParam.isAssistant()) {
        inputData.append("Assistant: ").append(messageParam.asAssistant().content());
      } else if (messageParam.isDeveloper()) {
        inputData.append("Developer: ").append(messageParam.asDeveloper().content());
      } else if (messageParam.isSystem()) {
        inputData.append("System: ").append(messageParam.asSystem().content());
      } else if (messageParam.isTool()) {
        inputData.append("Tool: ").append(messageParam.asTool().content());
      }
    }

    if (inputData.length() > 0) {
      llmObsSpan.annotateIO(inputData.toString(), null); // No output yet, will be set in response
    }

    java.util.Map<String, Object> metadata = new java.util.HashMap<>();
    metadata.put("endpoint", "/chat/completions");
    metadata.put("provider", "openai");
    metadata.put("model", modelName);

    params.maxTokens().ifPresent(tokens -> metadata.put("max_tokens", tokens));
    params.temperature().ifPresent(temp -> metadata.put("temperature", temp));

    llmObsSpan.setMetadata(metadata);

    return llmObsSpan;
  }

  public void finishLLMObsChatCompletionSpan(
      LLMObsSpan llmObsSpan, ChatCompletion response, Throwable throwable) {
    try {
      if (throwable != null) {
        // Set error information
        java.util.Map<String, Object> errorMetadata = new java.util.HashMap<>();
        errorMetadata.put("error.type", throwable.getClass().getSimpleName());
        errorMetadata.put("error.message", throwable.getMessage());
        llmObsSpan.setMetadata(errorMetadata);
      } else if (response != null) {
        StringBuilder outputData = new StringBuilder();
        List<ChatCompletion.Choice> choices = response.choices();

        for (int i = 0; i < choices.size(); i++) {
          ChatCompletion.Choice choice = choices.get(i);
          ChatCompletionMessage message = choice.message();

          if (i > 0) {
            outputData.append(" | ");
          }

          // Extract content
          Optional<String> content = message.content();
          content.ifPresent(s -> outputData.append("Assistant: ").append(s));

          // Extract tool calls if present
          Optional<List<ChatCompletionMessageToolCall>> toolCalls = message.toolCalls();
          if (toolCalls.isPresent() && !toolCalls.get().isEmpty()) {
            content.ifPresent(s -> outputData.append(" | "));
            outputData.append("Tool calls: ");
            for (int j = 0; j < toolCalls.get().size(); j++) {
              ChatCompletionMessageToolCall call = toolCalls.get().get(j);
              if (j > 0) {
                outputData.append(", ");
              }
              outputData
                  .append(call.function().name())
                  .append("(")
                  .append(call.function().arguments())
                  .append(")");
            }
          }
        }

        if (outputData.length() > 0) {
          llmObsSpan.annotateIO(null, outputData.toString());
        }
        java.util.Map<String, Object> responseMetadata = new java.util.HashMap<>();
        responseMetadata.put("response.choices_count", choices.size());

        llmObsSpan.setMetadata(responseMetadata);
      }
    } catch (Exception e) {
      java.util.Map<String, Object> errorMetadata = new java.util.HashMap<>();
      errorMetadata.put("error.type", "ResponseProcessingError");
      errorMetadata.put("error.message", "Failed to process response: " + e.getMessage());
      llmObsSpan.setMetadata(errorMetadata);
    } finally {
      // Always finish the span
      llmObsSpan.finish();
    }
  }

  public void finishSpan(AgentScope scope, Object result, Throwable throwable) {

    AgentSpan span = scope.span();

    try {
      if (throwable != null) {
        onError(span, throwable);
      } else if (result != null) {
        extractResponseData(span, result);
      }
      beforeFinish(span);
    } finally {
      scope.close();
      span.finish();
    }
  }

  private void extractChatCompletionRequestData(AgentSpan span, ChatCompletionCreateParams params) {

    span.setTag("openai.model.name", params.model().toString());

    // Extract messages
    List<ChatCompletionMessageParam> messages = params.messages();
    for (int i = 0; i < messages.size(); i++) {
      ChatCompletionMessageParam messageParam = messages.get(i);
      extractMessageData(span, messageParam, i);
    }

    // Extract request parameters
    extractChatCompletionParameters(span, params);
  }

  private void extractCompletionRequestData(
      AgentSpan span, CompletionCreateParams completionParams) {
    span.setTag("openai.model.name", completionParams.model().toString());

    // Extract prompt
    Optional<CompletionCreateParams.Prompt> prompt = completionParams.prompt();
    prompt.ifPresent(
        promptValue -> {
          if (promptValue.isArrayOfStrings()) {
            List<String> promptArray = promptValue.asArrayOfStrings();
            int promptIndex = 0;
            for (String currentPrompt : promptArray) {
              span.setTag("openai.request.prompt." + promptIndex, currentPrompt);
              promptIndex++;
            }
          } else if (promptValue.isString()) {
            // Setting the index as 0 since there is only one prompt string
            span.setTag("openai.request.prompt.0", promptValue.asString());

          } else if (promptValue.isArrayOfTokenArrays() || promptValue.isArrayOfTokens()) {
            // Setting the token array as the value of the prompt tag
            span.setTag("openai.request.prompt.0", promptValue.asArrayOfTokens());
          }
        });
    // Extract request parameters
    extractCompletionParameters(span, completionParams);
  }

  private void extractEmbeddingRequestData(AgentSpan span, EmbeddingCreateParams embeddingParams) {

    span.setTag("openai.model.name", embeddingParams.model().toString());
    // Extract input
    EmbeddingCreateParams.Input input = embeddingParams.input();
    int inputIndex = 0;
    List<String> inputStrings = input.asArrayOfStrings();
    for (String inputItem : inputStrings) {
      span.setTag("openai.request.input." + inputIndex, inputItem);
      inputIndex++;
    }
  }

  private void extractMessageData(
      AgentSpan span, ChatCompletionMessageParam messageParam, int index) {
    // Handle different message parameter types
    if (messageParam.isUser()) {
      span.setTag("openai.request.messages." + index + ".role", "user");
      span.setTag(
          "openai.request.messages." + index + ".content",
          messageParam.asUser().content().toString());
    } else if (messageParam.isAssistant()) {
      span.setTag("openai.request.messages." + index + ".role", "assistant");
      span.setTag(
          "openai.request.messages." + index + ".content",
          messageParam.asAssistant().content().toString());
    } else if (messageParam.isDeveloper()) {
      span.setTag("openai.request.messages." + index + ".role", "developer");
      span.setTag(
          "openai.request.messages." + index + ".content",
          messageParam.asDeveloper().content().toString());
    } else if (messageParam.isSystem()) {
      span.setTag("openai.request.messages." + index + ".role", "system");
      span.setTag(
          "openai.request.messages." + index + ".content",
          messageParam.asSystem().content().toString());
    } else if (messageParam.isTool()) {
      span.setTag("openai.request.messages." + index + ".role", "tool");
      span.setTag(
          "openai.request.messages." + index + ".content",
          messageParam.asTool().content().toString());
    }
  }

  private void extractChatCompletionParameters(AgentSpan span, ChatCompletionCreateParams params) {
    // Extract max_tokens
    params
        .maxCompletionTokens()
        .ifPresent(tokens -> span.setTag("openai.request.max_tokens", tokens));

    // Extract temperature
    params.temperature().ifPresent(temp -> span.setTag("openai.request.temperature", temp));
  }

  private void extractCompletionParameters(AgentSpan span, CompletionCreateParams params) {
    // Extract max_tokens
    params.maxTokens().ifPresent(tokens -> span.setTag("openai.request.max_tokens", tokens));
    // Extract temperature
    params.temperature().ifPresent(temp -> span.setTag("openai.request.temperature", temp));
  }

  private void extractResponseData(AgentSpan span, Object result) {
    if (result instanceof ChatCompletion) {
      extractChatCompletionResponseData(span, (ChatCompletion) result);
    } else if (result instanceof Completion) {
      extractCompletionResponseData(span, (Completion) result);
    } else if (result instanceof CreateEmbeddingResponse) {
      extractEmbeddingResponseData(span, (CreateEmbeddingResponse) result);
    }
  }

  private void extractChatCompletionResponseData(AgentSpan span, ChatCompletion response) {
    // Extract choices
    List<ChatCompletion.Choice> choices = response.choices();
    int choiceIndex = 0;
    if (!choices.isEmpty()) {
      for (ChatCompletion.Choice curChoice : choices) {
        ChatCompletionMessage curMessage = curChoice.message();
        // Extract content
        Optional<String> content = curMessage.content();
        content.ifPresent(
            s -> span.setTag("openai.response.choices." + choiceIndex + ".message.content", s));
        span.setTag(
            "openai.response.choices." + choiceIndex + ".message.role",
            curMessage._role().toString());
        Optional<List<ChatCompletionMessageToolCall>> toolCalls = curMessage.toolCalls();
        if (toolCalls.isPresent() && !toolCalls.get().isEmpty()) {
          // Extract tool calls if present
          int callIndex = 0;
          for (ChatCompletionMessageToolCall call : toolCalls.get()) {
            span.setTag(
                "openai.response.choices."
                    + choiceIndex
                    + ".message.tool_calls."
                    + callIndex
                    + ".name",
                call.id());
            span.setTag(
                "openai.response.choices."
                    + choiceIndex
                    + ".message.tool_calls."
                    + callIndex
                    + ".arguments",
                call.function().arguments());

            callIndex++;
          }
        }
      }
    }
  }

  private void extractCompletionResponseData(AgentSpan span, Completion response) {
    // Extract choices
    List<CompletionChoice> choices = response.choices();
    for (CompletionChoice choice : choices) {
      span.setTag("openai.response.choices." + choice.index() + ".text", choice.text());
    }
  }

  private void extractEmbeddingResponseData(AgentSpan span, CreateEmbeddingResponse response) {
    // Extract data (embeddings)
    List<Embedding> data = response.data();
    span.setTag("openai.response.embeddings_count", data.size());
    int embeddingIndex = 0;
    if (!data.isEmpty()) {
      for (Embedding curEmbedding : data) {
        // Extract embedding array
        List<Float> embedding = curEmbedding.embedding();
        span.setTag(
            "openai.response.embedding." + embeddingIndex + ".embedding_length", embedding.size());
        embeddingIndex++;
      }
    }
  }
}
