package com.datadog.aiguard;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;
import static datadog.http.client.HttpRequest.APPLICATION_JSON;
import static datadog.http.client.HttpRequest.CONTENT_TYPE;
import static datadog.trace.api.telemetry.WafMetricCollector.AIGuardTruncationType.CONTENT;
import static datadog.trace.api.telemetry.WafMetricCollector.AIGuardTruncationType.MESSAGES;
import static datadog.trace.util.Strings.isBlank;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.api.aiguard.AIGuard.AIGuardAbortError;
import datadog.trace.api.aiguard.AIGuard.AIGuardClientError;
import datadog.trace.api.aiguard.AIGuard.Action;
import datadog.trace.api.aiguard.AIGuard.ContentPart;
import datadog.trace.api.aiguard.AIGuard.Evaluation;
import datadog.trace.api.aiguard.AIGuard.Message;
import datadog.trace.api.aiguard.AIGuard.Options;
import datadog.trace.api.aiguard.AIGuard.ToolCall;
import datadog.trace.api.aiguard.AIGuard.ToolCall.Function;
import datadog.trace.api.aiguard.Evaluator;
import datadog.trace.api.aiguard.noop.NoOpEvaluator;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import okio.Buffer;

/**
 * Concrete implementation of the SDK used to interact with the AIGuard REST API.
 *
 * <p>An instance of this class is initialized and configured automatically during agent startup
 * through {@link AIGuardSystem#start()}.
 */
public class AIGuardInternal implements Evaluator {

  public static class BadConfigurationException extends RuntimeException {
    public BadConfigurationException(final String message) {
      super(message);
    }
  }

  static final String SPAN_NAME = "ai_guard";
  static final String TARGET_TAG = "ai_guard.target";
  static final String TOOL_TAG = "ai_guard.tool_name";
  static final String ACTION_TAG = "ai_guard.action";
  static final String REASON_TAG = "ai_guard.reason";
  static final String BLOCKED_TAG = "ai_guard.blocked";
  static final String META_STRUCT_TAG = "ai_guard";
  static final String META_STRUCT_MESSAGES = "messages";
  static final String META_STRUCT_CATEGORIES = "attack_categories";

  public static void install() {
    final Config config = Config.get();
    final String apiKey = config.getApiKey();
    final String appKey = config.getApplicationKey();
    if (isBlank(apiKey) || isBlank(appKey)) {
      throw new BadConfigurationException(
          "AI Guard: Missing api and/or application key, use DD_API_KEY and DD_APP_KEY");
    }
    String endpoint = config.getAiGuardEndpoint();
    if (isBlank(endpoint)) {
      endpoint = String.format("https://app.%s/api/v2/ai-guard", config.getSite());
    }
    final Map<String, String> headers =
        mapOf(
            "DD-API-KEY",
            apiKey,
            "DD-APPLICATION-KEY",
            appKey,
            "DD-AI-GUARD-VERSION",
            TRACER_VERSION,
            "DD-AI-GUARD-SOURCE",
            "SDK",
            "DD-AI-GUARD-LANGUAGE",
            "jvm");
    final HttpUrl url = HttpUrl.parse(endpoint).newBuilder().addPathSegment("evaluate").build();
    final int timeout = config.getAiGuardTimeout();
    final HttpClient client = buildClient(url, timeout);
    Installer.install(new AIGuardInternal(url, headers, client));
  }

  /** Used by tests to reset status */
  static void uninstall() {
    Installer.install(new NoOpEvaluator());
  }

  private final HttpUrl url;
  private final Moshi moshi;
  private final HttpClient client;
  private final Map<String, String> meta;
  private final Map<String, String> headers;

  AIGuardInternal(final HttpUrl url, final Map<String, String> headers, final HttpClient client) {
    this.url = url;
    this.headers = headers;
    this.client = client;
    this.moshi = new Moshi.Builder().add(new AIGuardFactory()).build();
    final Config config = Config.get();
    this.meta = mapOf("service", config.getServiceName(), "env", config.getEnv());
  }

  /**
   * Creates a deep copy of the messages before storing them in the metastruct to avoid concurrent
   * modifications prior to trace serialization.
   */
  private static List<Message> messagesForMetaStruct(List<Message> messages) {
    final Config config = Config.get();
    final int size = Math.min(messages.size(), config.getAiGuardMaxMessagesLength());
    if (size < messages.size()) {
      WafMetricCollector.get().aiGuardTruncated(MESSAGES);
    }
    final List<Message> result = new ArrayList<>(size);
    final int maxContent = config.getAiGuardMaxContentSize();
    boolean contentTruncated = false;
    for (int i = messages.size() - size; i < messages.size(); i++) {
      final Message source = messages.get(i);

      List<ToolCall> toolCalls = source.getToolCalls();
      if (toolCalls != null) {
        toolCalls = new ArrayList<>(toolCalls);
      }

      List<ContentPart> contentParts = source.getContentParts();
      if (contentParts != null) {
        final List<ContentPart> truncatedParts = new ArrayList<>(contentParts.size());
        for (final ContentPart part : contentParts) {
          if (part.getType() == ContentPart.Type.TEXT
              && part.getText() != null
              && part.getText().length() > maxContent) {
            contentTruncated = true;
            final String text = part.getText().substring(0, maxContent);
            truncatedParts.add(ContentPart.text(text));
          } else {
            truncatedParts.add(part);
          }
        }

        result.add(
            new Message(source.getRole(), truncatedParts, toolCalls, source.getToolCallId()));
      } else {
        String content = source.getContent();
        if (content != null && content.length() > maxContent) {
          contentTruncated = true;
          content = content.substring(0, maxContent);
        }
        result.add(new Message(source.getRole(), content, toolCalls, source.getToolCallId()));
      }
    }
    if (contentTruncated) {
      WafMetricCollector.get().aiGuardTruncated(CONTENT);
    }
    return result;
  }

  private static boolean isToolCall(final Message message) {
    return message.getToolCalls() != null || message.getToolCallId() != null;
  }

  private static String getToolName(final Message current, final List<Message> messages) {
    if (current.getToolCalls() != null) {
      // assistant message with tool calls
      return current.getToolCalls().stream()
          .map(ToolCall::getFunction)
          .map(Function::getName)
          .collect(Collectors.joining(","));
    }
    // assistant message with tool output (search the linked tool call in reverse order)
    final String id = current.getToolCallId();
    for (int i = messages.size() - 1; i >= 0; i--) {
      final Message message = messages.get(i);
      if (message.getToolCalls() != null) {
        for (final ToolCall toolCall : message.getToolCalls()) {
          if (toolCall.getId().equals(id)) {
            return toolCall.getFunction() == null ? null : toolCall.getFunction().getName();
          }
        }
      }
    }
    return null;
  }

  private boolean isBlockingEnabled(final Options options, final Object isBlockingEnabled) {
    return options.block() && "true".equalsIgnoreCase(isBlockingEnabled.toString());
  }

  @Override
  public Evaluation evaluate(final List<Message> messages, final Options options) {
    if (messages == null || messages.isEmpty()) {
      throw new IllegalArgumentException("Messages must not be empty");
    }
    final AgentTracer.TracerAPI tracer = AgentTracer.get();
    final AgentTracer.SpanBuilder builder = tracer.buildSpan(SPAN_NAME, SPAN_NAME);
    final AgentSpan parent = AgentTracer.activeSpan();
    if (parent != null) {
      builder.asChildOf(parent.context());
    }
    final AgentSpan span = builder.start();
    final AgentSpan localRootSpan = span.getLocalRootSpan();
    if (localRootSpan != null) {
      localRootSpan.setTag(DDTags.MANUAL_KEEP, true);
    }
    try (final AgentScope scope = tracer.activateSpan(span)) {
      final Message last = messages.get(messages.size() - 1);
      if (isToolCall(last)) {
        span.setTag(TARGET_TAG, "tool");
        final String toolName = getToolName(last, messages);
        if (toolName != null) {
          span.setTag(TOOL_TAG, toolName);
        }
      } else {
        span.setTag(TARGET_TAG, "prompt");
      }
      final Map<String, Object> metaStruct = new HashMap<>(2);
      metaStruct.put(META_STRUCT_MESSAGES, messagesForMetaStruct(messages));
      span.setMetaStruct(META_STRUCT_TAG, metaStruct);
      final HttpRequest.Builder request = HttpRequest.newBuilder()
              .url(url)
              .header(CONTENT_TYPE, APPLICATION_JSON)
              .post(HttpRequestBody.of(formatRequestBody(moshi, messages, meta)));
      headers.forEach(request::header);
      try (final HttpResponse response = client.execute(request.build())) {
        final Map<String, Object> result = parseResponseBody(response);
        final String actionStr = (String) result.get("action");
        if (actionStr == null) {
          throw new IllegalArgumentException("Action field is missing in the response");
        }
        final Action action = Action.valueOf(actionStr);
        final String reason = (String) result.get("reason");
        @SuppressWarnings("unchecked")
        final List<String> tags = (List<String>) result.get("tags");
        span.setTag(ACTION_TAG, action);
        if (reason != null) {
          span.setTag(REASON_TAG, reason);
        }
        if (tags != null && !tags.isEmpty()) {
          metaStruct.put(META_STRUCT_CATEGORIES, tags);
        }
        final boolean shouldBlock =
            isBlockingEnabled(options, result.get("is_blocking_enabled")) && action != Action.ALLOW;
        WafMetricCollector.get().aiGuardRequest(action, shouldBlock);
        if (shouldBlock) {
          span.setTag(BLOCKED_TAG, true);
          throw new AIGuardAbortError(action, reason, tags);
        }
        return new Evaluation(action, reason, tags);
      }
    } catch (AIGuardAbortError e) {
      span.addThrowable(e);
      throw e;
    } catch (AIGuardClientError e) {
      WafMetricCollector.get().aiGuardError();
      span.addThrowable(e);
      throw e;
    } catch (final Exception e) {
      WafMetricCollector.get().aiGuardError();
      final AIGuardClientError error =
          new AIGuardClientError(
              "AI Guard service returned unexpected response: " + e.getMessage(), e);
      span.addThrowable(error);
      throw error;
    } finally {
      span.finish();
    }
  }

  private String formatRequestBody(final Moshi moshi, final Collection<Message> messages, final Map<String, String> meta) throws IOException{
    Buffer sink = new Buffer();
    final JsonWriter writer = JsonWriter.of(sink);
    writer.beginObject(); // request
    writer.name("data");
    writer.beginObject(); // data
    writer.name("attributes");
    writer.beginObject(); // attributes
    writer.name("messages");
    moshi.adapter(Object.class).toJson(writer, messages);
    writer.name("meta");
    writer.jsonValue(meta);
    writer.endObject(); // attributes
    writer.endObject(); // data
    writer.endObject(); // request
    return sink.readUtf8();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseResponseBody(final HttpResponse response) throws IOException {
    final Map<?, ?> parsedBody = moshi.adapter(Map.class).fromJson(response.bodyAsString());
    final Object errors = parsedBody.get("errors");
    if (errors != null) {
      throw fail(response.code(), errors);
    }
    final Map<?, ?> data = (Map<?, ?>) parsedBody.get("data");
    return (Map<String, Object>) data.get("attributes");
  }

  private AIGuardClientError fail(final int statusCode, final Object errors) {
    return new AIGuardClientError("AI Guard service call failed, status: " + statusCode, errors);
  }

  private static HttpClient buildClient(final HttpUrl url, final long timeout) {
    return HttpUtils.buildHttpClient(url, timeout);
  }

  private static Map<String, String> mapOf(final String... props) {
    if (props.length % 2 != 0) {
      throw new IllegalArgumentException("Props must be even");
    }
    final Map<String, String> map = new HashMap<>(props.length << 1);
    for (int i = 0; i < props.length; ) {
      map.put(props[i++], props[i++]);
    }
    return map;
  }

  private static class Installer extends AIGuard {
    public static void install(final Evaluator evaluator) {
      AIGuard.EVALUATOR = evaluator;
    }
  }

  static class AIGuardFactory implements JsonAdapter.Factory {

    @Nullable
    @Override
    public JsonAdapter<?> create(
        final Type type, final Set<? extends Annotation> annotations, final Moshi moshi) {
      final Class<?> rawType = Types.getRawType(type);
      if (rawType != AIGuard.Message.class) {
        return null;
      }
      return new MessageAdapter(moshi.adapter(AIGuard.ToolCall.class)).nullSafe();
    }
  }

  static class MessageAdapter extends JsonAdapter<Message> {

    private final JsonAdapter<AIGuard.ToolCall> toolCallAdapter;

    MessageAdapter(final JsonAdapter<ToolCall> toolCallAdapter) {
      this.toolCallAdapter = toolCallAdapter;
    }

    @Nullable
    @Override
    public Message fromJson(JsonReader reader) throws IOException {
      throw new UnsupportedOperationException("Serializing only adapter");
    }

    @Override
    public void toJson(final JsonWriter writer, final Message value) throws IOException {
      writer.beginObject();
      writeValue(writer, "role", value.getRole());

      if (value.getContentParts() != null) {
        writeContentParts(writer, "content", value.getContentParts());
      } else {
        writeValue(writer, "content", value.getContent());
      }

      writeArray(writer, "tool_calls", value.getToolCalls());
      writeValue(writer, "tool_call_id", value.getToolCallId());
      writer.endObject();
    }

    private void writeContentParts(
        final JsonWriter writer, final String name, final List<ContentPart> contentParts)
        throws IOException {
      writer.name(name);
      writer.beginArray();
      for (final ContentPart part : contentParts) {
        writer.beginObject();

        writer.name("type");
        writer.value(part.getType().toString());

        if (part.getType() == ContentPart.Type.TEXT) {
          writer.name("text");
          writer.value(part.getText());
        } else if (part.getType() == ContentPart.Type.IMAGE_URL) {
          writer.name("image_url");
          writer.beginObject();
          writer.name("url");
          writer.value(part.getImageUrl().getUrl());
          writer.endObject();
        }

        writer.endObject();
      }
      writer.endArray();
    }

    private void writeValue(final JsonWriter writer, final String name, final Object value)
        throws IOException {
      if (value != null) {
        writer.name(name);
        writer.jsonValue(value);
      }
    }

    private void writeArray(final JsonWriter writer, final String name, final List<ToolCall> value)
        throws IOException {
      if (value != null) {
        writer.name(name);
        writer.beginArray();
        for (final ToolCall toolCall : value) {
          toolCallAdapter.toJson(writer, toolCall);
        }
        writer.endArray();
      }
    }
  }
}
