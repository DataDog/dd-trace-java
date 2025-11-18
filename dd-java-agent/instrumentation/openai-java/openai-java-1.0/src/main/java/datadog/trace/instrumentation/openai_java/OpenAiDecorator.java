package datadog.trace.instrumentation.openai_java;

import com.openai.core.ClientOptions;
import com.openai.core.JsonField;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponse;
import com.openai.models.ResponsesModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.List;
import java.util.Optional;

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
    span.setTag("_ml_obs_tag.parent_id", currentLlmParentSpanId()); // TODO duplicates DDLLMObsSpan, test in LLMObsSpanMapperTest
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

    // TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithCompletion(AgentSpan span, Completion completion) {
    span.setTag(RESPONSE_MODEL, completion.model());

    // TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithCompletions(AgentSpan span, List<Completion> completions) {
    if (!completions.isEmpty()) {
      span.setTag(RESPONSE_MODEL, completions.get(0).model());
    }

    // TODO set LLMObs tags (not visible to APM)
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

  public void decorateChatCompletion(AgentSpan span, ChatCompletionCreateParams params) {
    span.setResourceName(CHAT_COMPLETIONS_CREATE);
    span.setTag("openai.request.endpoint", "v1/chat/completions");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    span.setTag(REQUEST_MODEL, params.model().asString()); // TODO extract model, might not be set
  }

  public void decorateWithChatCompletion(AgentSpan span, ChatCompletion completion) {
    span.setTag(RESPONSE_MODEL, completion.model());

    // TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithChatCompletionChunks(AgentSpan span, List<ChatCompletionChunk> chunks) {
    if (!chunks.isEmpty()) {
      span.setTag(RESPONSE_MODEL, chunks.get(0).model());
    }

    // TODO set LLMObs tags (not visible to APM)
  }

  public void decorateEmbedding(AgentSpan span, EmbeddingCreateParams params) {
    span.setResourceName(EMBEDDINGS_CREATE);
    span.setTag("openai.request.endpoint", "v1/embeddings");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    span.setTag(REQUEST_MODEL, params.model().asString()); // TODO extract model, might not be set

    // TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithEmbedding(AgentSpan span, CreateEmbeddingResponse response) {
    span.setTag(RESPONSE_MODEL, response.model());

    // TODO set LLMObs tags (not visible to APM)
  }

  public void decorateResponse(AgentSpan span, ResponseCreateParams params) {
    span.setResourceName(RESPONSES_CREATE);
    span.setTag("openai.request.endpoint", "v1/responses");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    span.setTag(REQUEST_MODEL, extractResponseModel(params._model()));
  }

  private String extractResponseModel(JsonField<ResponsesModel> model) {
    Optional<String> result = model.asString();
    if (result.isPresent()) {
      return result.get();
    }
    result = model.asKnown().flatMap(k -> k.chat().flatMap(v -> v._value().asString()));
    return result.orElse("_UNKNOWN");
  }

  public void decorateWithResponse(AgentSpan span, Response response) {
    span.setTag(RESPONSE_MODEL, extractResponseModel(response._model()));

    // TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithResponseStreamEvent(AgentSpan span, List<ResponseStreamEvent> events) {
    if (!events.isEmpty()) {
      // ResponseStreamEvent responseStreamEvent = events.get(0);
      // span.setTag(RESPONSE_MODEL, responseStreamEvent.res()); // TODO there is no model
    }

    // TODO set LLMObs tags (not visible to APM)
  }
}
