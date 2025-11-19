package datadog.trace.instrumentation.openai_java;

import com.openai.core.ClientOptions;
import com.openai.core.JsonField;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponse;
import com.openai.models.ResponsesModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class OpenAiDecorator extends ClientDecorator {
  public static final OpenAiDecorator DECORATE = new OpenAiDecorator();

  public static final String INSTRUMENTATION_NAME = "openai-java";
  public static final CharSequence SPAN_NAME = UTF8BytesString.create("openai.request");

  public static final String REQUEST_MODEL = "openai.request.model";
  public static final String RESPONSE_MODEL = "openai.response.model";
  public static final String OPENAI_ORGANIZATION_NAME = "openai.organization";

  private static final CharSequence COMPLETIONS_CREATE = UTF8BytesString.create("createCompletion");
  private static final CharSequence CHAT_COMPLETIONS_CREATE =
      UTF8BytesString.create("createChatCompletion");
  private static final CharSequence EMBEDDINGS_CREATE = UTF8BytesString.create("createEmbedding");
  private static final CharSequence RESPONSES_CREATE = UTF8BytesString.create("createResponse");
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("openai");

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION_NAME};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.LLMOBS;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    span.setTag(
        "_ml_obs_tag.parent_id",
        currentLlmParentSpanId()); // TODO duplicates DDLLMObsSpan, test in LLMObsSpanMapperTest
    span.setTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND); // TODO also see DDLLMObsSpan
    return super.afterStart(span);
  }

  private String currentLlmParentSpanId() {
    AgentSpanContext parentLlmContext = LLMObsContext.current();
    if (parentLlmContext == null) {
      return LLMObsContext.ROOT_SPAN_ID;
    }
    long parentLlmSpanId = parentLlmContext.getSpanId();
    if (parentLlmSpanId == DDSpanId.ZERO) {
      return LLMObsContext.ROOT_SPAN_ID;
    }
    return Long.toString(parentLlmSpanId);
  }

  public void decorateCompletion(AgentSpan span, CompletionCreateParams params) {
    span.setResourceName(COMPLETIONS_CREATE);
    span.setTag("openai.request.endpoint", "v1/completions");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }

    span.setTag(REQUEST_MODEL, params.model().asString()); // TODO extract model, might not be set
    params
        .prompt()
        .flatMap(p -> p.string())
        .ifPresent(
            input ->
                span.setTag(
                    "_ml_obs_tag.input",
                    Collections.singletonList(LLMObs.LLMMessage.from(null, input))));

    Map<String, Object> metadata = new HashMap<>();
    params.maxTokens().ifPresent(v -> metadata.put("max_tokens", v));
    params.temperature().ifPresent(v -> metadata.put("temperature", v));
    span.setTag("_ml_obs_tag.metadata", metadata);
  }

  public void decorateWithCompletion(AgentSpan span, Completion completion) {
    String modelName = completion.model();
    span.setTag(RESPONSE_MODEL, modelName);
    span.setTag("_ml_obs_tag.model_name", modelName);
    span.setTag("_ml_obs_tag.model_provider", "openai");
    // span.setTag("_ml_obs_tag.model_version", ); // TODO split and set version, e.g.
    // gpt-3.5-turbo-instruct:20230824-v2

    List<LLMObs.LLMMessage> output =
        completion.choices().stream()
            .map(v -> LLMObs.LLMMessage.from(null, v.text()))
            .collect(Collectors.toList());
    span.setTag("_ml_obs_tag.output", output);

    completion.usage().ifPresent(usage -> OpenAiDecorator.annotateWithCompletionUsage(span, usage));
  }

  public void decorateWithCompletions(AgentSpan span, List<Completion> completions) {
    if (!completions.isEmpty()) {
      decorateWithCompletion(span, completions.get(0));
    }
  }

  public void decorateWithHttpResponse(AgentSpan span, HttpResponse response) {
    Headers headers = response.headers();
    setTagFromHeader(span, OPENAI_ORGANIZATION_NAME, headers, "openai-organization");

    setMetricFromHeader(
        span,
        "openai.organization.ratelimit.requests.limit",
        headers,
        "x-ratelimit-limit-requests");
    setMetricFromHeader(
        span,
        "openai.organization.ratelimit.requests.remaining",
        headers,
        "x-ratelimit-remaining-requests");
    setMetricFromHeader(
        span, "openai.organization.ratelimit.tokens.limit", headers, "x-ratelimit-limit-tokens");
    setMetricFromHeader(
        span,
        "openai.organization.ratelimit.tokens.remaining",
        headers,
        "x-ratelimit-remaining-tokens");
  }

  private static void setTagFromHeader(AgentSpan span, String tag, Headers headers, String header) {
    List<String> values = headers.values(header);
    if (values.isEmpty()) {
      return;
    }
    span.setTag(tag, values.get(0));
  }

  private static void setMetricFromHeader(
      AgentSpan span, String metric, Headers headers, String header) {
    List<String> values = headers.values(header);
    if (values.isEmpty()) {
      return;
    }
    String firstHeader = values.get(0);
    try {
      int value = Integer.parseInt(firstHeader);
      span.setMetric(metric, value);
    } catch (NumberFormatException ex) {
      // ~
    }
  }

  public void decorateWithClientOptions(AgentSpan span, ClientOptions clientOptions) {
    span.setTag("openai.api_base", clientOptions.baseUrl());

    // TODO api_version (either last part of the URL, or api-version param if Azure)
    // clientOptions.queryParams().values("api-version")
  }

  public void decorateChatCompletion(AgentSpan span, ChatCompletionCreateParams params, boolean stream) {
    span.setResourceName(CHAT_COMPLETIONS_CREATE);
    span.setTag("openai.request.endpoint", "v1/chat/completions");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    span.setTag(REQUEST_MODEL, params.model().asString()); // TODO extract model, might not be set

    span.setTag(
        "_ml_obs_tag.input",
        params.messages().stream().map(OpenAiDecorator::llmMessage).collect(Collectors.toList()));

    Map<String, Object> metadata = new HashMap<>();
    // maxTokens is deprecated but integration tests missing to provide maxCompletionTokens
    params.maxTokens().ifPresent(v -> metadata.put("max_tokens", v));
    params.temperature().ifPresent(v -> metadata.put("temperature", v));
    if (stream) {
      metadata.put("stream", true);
    }
    params.streamOptions().ifPresent(v -> {
      if (v.includeUsage().orElse(false)) {
        metadata.put("stream_options", Collections.singletonMap("include_usage", true));
      }
    });
    span.setTag("_ml_obs_tag.metadata", metadata);
  }

  private static LLMObs.LLMMessage llmMessage(ChatCompletionMessageParam m) {
    String role = "unknown";
    String content = null;
    if (m.isAssistant()) {
      role = "assistant";
      content = m.asAssistant().content().map(v -> v.text().orElse(null)).orElse(null);
    } else if (m.isDeveloper()) {
      role = "developer";
      content = m.asDeveloper().content().text().orElse(null);
    } else if (m.isSystem()) {
      role = "system";
      content = m.asSystem().content().text().orElse(null);
    } else if (m.isTool()) {
      role = "tool";
      content = m.asTool().content().text().orElse(null);
    } else if (m.isUser()) {
      role = "user";
      content = m.asUser().content().text().orElse(null);
    }
    return LLMObs.LLMMessage.from(role, content);
  }

  public void decorateWithChatCompletion(AgentSpan span, ChatCompletion completion) {
    String modelName = completion.model();
    span.setTag(RESPONSE_MODEL, modelName);

    span.setTag("_ml_obs_tag.model_name", modelName);
    span.setTag("_ml_obs_tag.model_provider", "openai");
    // span.setTag("_ml_obs_tag.model_version", ); // TODO split and set version, e.g.
    // gpt-3.5-turbo-instruct:20230824-v2c

    List<LLMObs.LLMMessage> output =
        completion.choices().stream()
            .map(OpenAiDecorator::llmMessage)
            .collect(Collectors.toList());
    span.setTag("_ml_obs_tag.output", output);

    completion.usage().ifPresent(usage -> OpenAiDecorator.annotateWithCompletionUsage(span, usage));
  }

  private static void annotateWithCompletionUsage(AgentSpan span, CompletionUsage usage) {
    span.setTag("_ml_obs_metric.input_tokens", usage.promptTokens());
    span.setTag("_ml_obs_metric.output_tokens", usage.completionTokens());
    span.setTag("_ml_obs_metric.total_tokens", usage.totalTokens());
  }

  private static LLMObs.LLMMessage llmMessage(ChatCompletion.Choice choice) {
    ChatCompletionMessage msg = choice.message();
    Optional<?> roleOpt = msg._role().asString();
    String role = "unknown";
    if (roleOpt.isPresent()) {
      role = String.valueOf(roleOpt.get());
    }
    String content = msg.content().orElse(null);
    return LLMObs.LLMMessage.from(role, content);
  }

  public void decorateWithChatCompletionChunks(AgentSpan span, List<ChatCompletionChunk> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
    ChatCompletionChunk firstChunk = chunks.get(0);
    String modelName = firstChunk.model();
    span.setTag(RESPONSE_MODEL, modelName);

    span.setTag("_ml_obs_tag.model_name", modelName);
    span.setTag("_ml_obs_tag.model_provider", "openai");
    // span.setTag("_ml_obs_tag.model_version", ); // TODO split and set version, e.g.
    // gpt-3.5-turbo-instruct:20230824-v2

    // assume that number of choices is the same for each chunk
    final int choiceNum = firstChunk.choices().size();
    // collect roles by choices by the first chunk
    String[] roles = new String[choiceNum];
    for (int i=0; i < choiceNum; i++) {
      ChatCompletionChunk.Choice choice = firstChunk.choices().get(i);
      Optional<String> role = choice.delta().role().flatMap(r -> r._value().asString());
      if (role.isPresent()) {
        roles[i] = role.get();
      }
    }
    // collect content by choices for all chunks
    StringBuilder[] contents = new StringBuilder[choiceNum];
    for (int i=0; i < choiceNum; i++) {
      contents[i] = new StringBuilder(128);
    }
    for (ChatCompletionChunk chunk : chunks) {
      // choices can be empty for the last chunk
      List<ChatCompletionChunk.Choice> choices = chunk.choices();
      for (int i=0; i < choiceNum && i < choices.size(); i++) {
        ChatCompletionChunk.Choice choice = choices.get(i);
        ChatCompletionChunk.Choice.Delta delta = choice.delta();
        delta.content().ifPresent(contents[i]::append);
      }
      chunk.usage().ifPresent(usage -> OpenAiDecorator.annotateWithCompletionUsage(span, usage));
    }
    // build LLMMessages
    List<LLMObs.LLMMessage> llmMessages = new ArrayList<>(choiceNum);
    for (int i=0; i < choiceNum; i++) {
      llmMessages.add(LLMObs.LLMMessage.from(roles[i], contents[i].toString()));
    }
    span.setTag("_ml_obs_tag.output", llmMessages);
  }

  public void decorateEmbedding(AgentSpan span, EmbeddingCreateParams params) {
    span.setResourceName(EMBEDDINGS_CREATE);
    span.setTag("openai.request.endpoint", "v1/embeddings");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    span.setTag(REQUEST_MODEL, params.model().asString()); // TODO extract model, might not be set

    // TODO set LLMObs tags
  }

  public void decorateWithEmbedding(AgentSpan span, CreateEmbeddingResponse response) {
    span.setTag(RESPONSE_MODEL, response.model());

    // TODO set LLMObs tags
  }

  public void decorateResponse(AgentSpan span, ResponseCreateParams params) {
    span.setResourceName(RESPONSES_CREATE);
    span.setTag("openai.request.endpoint", "v1/responses");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    // Use ResponseCreateParams._model() b/o ResponseCreateParams.model() changed type from
    // ResponsesModel to Optional<ResponsesModel> in
    // https://github.com/openai/openai-java/commit/87dd64658da6cec7564f3b571e15ec0e2db0660b
    span.setTag(REQUEST_MODEL, extractResponseModel(params._model()));
  }

  public void decorateWithResponse(AgentSpan span, Response response) {
    span.setTag(RESPONSE_MODEL, extractResponseModel(response._model()));

    // TODO set LLMObs tags
  }

  private String extractResponseModel(JsonField<ResponsesModel> model) {
    Optional<String> str = model.asString();
    if (str.isPresent()) {
      return str.get();
    }
    Optional<ResponsesModel> known = model.asKnown();
    if (known.isPresent()) {
      ResponsesModel m = known.get();
      if (m.isString()) {
        return m.asString();
      }
      if (m.isChat()) {
        Optional<String> s = m.asChat()._value().asString();
        if (s.isPresent()) {
          return s.get();
        }
      }
      if (m.isOnly()) {
        Optional<String> s = m.asOnly()._value().asString();
        if (s.isPresent()) {
          return s.get();
        }
      }
    }
    return null;
  }

  public void decorateWithResponseStreamEvent(AgentSpan span, List<ResponseStreamEvent> events) {
    if (!events.isEmpty()) {
      // ResponseStreamEvent responseStreamEvent = events.get(0);
      // span.setTag(RESPONSE_MODEL, responseStreamEvent.res()); // TODO there is no model
    }

    // TODO set LLMObs tags
  }
}
