package datadog.trace.api.llmobs;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Locale;

/**
 * Emits the scalar {@code gen_ai.*} attributes of an LLM Observability span onto the APM span, so
 * model, provider, application, conversation and token usage are searchable in APM. Message bodies
 * stay off the APM span and keep coming from the LLM Observability track.
 */
public final class GenAiApmTags {
  public static final String OPERATION_NAME = "gen_ai.operation.name";
  public static final String REQUEST_MODEL = "gen_ai.request.model";
  public static final String PROVIDER_NAME = "gen_ai.provider.name";
  public static final String APPLICATION_NAME = "gen_ai.application.name";
  public static final String CONVERSATION_ID = "gen_ai.conversation.id";

  public static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
  public static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
  public static final String USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";
  public static final String USAGE_CACHE_READ_INPUT_TOKENS = "gen_ai.usage.cache_read_input_tokens";
  public static final String USAGE_CACHE_WRITE_INPUT_TOKENS =
      "gen_ai.usage.cache_write_input_tokens";
  public static final String USAGE_REASONING_OUTPUT_TOKENS = "gen_ai.usage.reasoning_output_tokens";

  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric.";

  private static final String SPAN_KIND_TAG = LLMOBS_TAG_PREFIX + Tags.SPAN_KIND;
  private static final String MODEL_NAME_TAG = LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_NAME;
  private static final String MODEL_PROVIDER_TAG = LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_PROVIDER;
  private static final String ML_APP_TAG = LLMOBS_TAG_PREFIX + LLMObsTags.ML_APP;
  private static final String SESSION_ID_TAG = LLMOBS_TAG_PREFIX + LLMObsTags.SESSION_ID;

  /** Matches the fallback the LLM Observability event uses. */
  private static final String DEFAULT_MODEL = "custom";

  /** LLM Observability metric name paired with the {@code gen_ai.usage.*} key it maps to. */
  private static final String[][] TOKEN_METRICS = {
    {LLMOBS_METRIC_PREFIX + "input_tokens", USAGE_INPUT_TOKENS},
    {LLMOBS_METRIC_PREFIX + "output_tokens", USAGE_OUTPUT_TOKENS},
    {LLMOBS_METRIC_PREFIX + "total_tokens", USAGE_TOTAL_TOKENS},
    {LLMOBS_METRIC_PREFIX + "cache_read_input_tokens", USAGE_CACHE_READ_INPUT_TOKENS},
    {LLMOBS_METRIC_PREFIX + "cache_write_input_tokens", USAGE_CACHE_WRITE_INPUT_TOKENS},
    {LLMOBS_METRIC_PREFIX + "reasoning_output_tokens", USAGE_REASONING_OUTPUT_TOKENS},
  };

  /**
   * Writes the attributes onto a span that is not yet finished, reading them back from its {@code
   * _ml_obs_tag.} / {@code _ml_obs_metric.} tags. No-op for a span with no LLM Observability kind.
   */
  public static void apply(AgentSpan span) {
    if (span == null) {
      return;
    }
    String spanKind = stringTag(span, SPAN_KIND_TAG);
    if (spanKind == null) {
      return;
    }
    setScalars(
        span,
        spanKind,
        stringTag(span, MODEL_NAME_TAG),
        stringTag(span, MODEL_PROVIDER_TAG),
        stringTag(span, ML_APP_TAG),
        stringTag(span, SESSION_ID_TAG));

    // Other kinds carry unrelated metrics that a gen_ai.usage.* key would misrepresent.
    if (isModelBacked(spanKind)) {
      for (String[] metric : TOKEN_METRICS) {
        Object value = span.getTag(metric[0]);
        if (value instanceof Number) {
          span.setMetric(metric[1], ((Number) value).doubleValue());
        }
      }
    }
  }

  /**
   * Writes the subset available with LLM Observability disabled. Token usage and conversation id
   * are never computed on that path, so they are left out.
   */
  public static void applyWithoutLlmObs(
      AgentSpan span, String operationName, String modelName, String modelProvider, String mlApp) {
    if (span == null || operationName == null) {
      return;
    }
    setScalars(
        span,
        operationName,
        emptyToNull(modelName),
        emptyToNull(modelProvider),
        emptyToNull(mlApp),
        null);
  }

  private static void setScalars(
      AgentSpan span,
      String operationName,
      String modelName,
      String modelProvider,
      String mlApp,
      String sessionId) {
    span.setTag(OPERATION_NAME, operationName);

    if (isModelBacked(operationName)) {
      span.setTag(REQUEST_MODEL, modelName == null ? DEFAULT_MODEL : modelName);
      span.setTag(
          PROVIDER_NAME,
          (modelProvider == null ? DEFAULT_MODEL : modelProvider).toLowerCase(Locale.ROOT));
    } else {
      if (modelName != null) {
        span.setTag(REQUEST_MODEL, modelName);
      }
      if (modelProvider != null) {
        span.setTag(PROVIDER_NAME, modelProvider.toLowerCase(Locale.ROOT));
      }
    }

    if (mlApp != null) {
      span.setTag(APPLICATION_NAME, mlApp);
    }
    if (sessionId != null) {
      span.setTag(CONVERSATION_ID, sessionId);
    }
  }

  private static boolean isModelBacked(String spanKind) {
    return Tags.LLMOBS_LLM_SPAN_KIND.equals(spanKind)
        || Tags.LLMOBS_EMBEDDING_SPAN_KIND.equals(spanKind);
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static String stringTag(AgentSpan span, String key) {
    Object value = span.getTag(key);
    if (value == null) {
      return null;
    }
    String string = String.valueOf(value);
    return string.isEmpty() ? null : string;
  }

  private GenAiApmTags() {}
}
