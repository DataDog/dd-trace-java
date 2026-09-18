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

  /**
   * Marks the {@code gen_ai.*} attributes as written by the tracer rather than by the user. Without
   * it, the backend processor treats the attributes as a user annotation and creates a duplicate
   * LLM Observability span.
   */
  public static final String ARTIFICIAL_TAGS = "_dd.llmobs.artificial_gen_ai_tags";

  private static final String ARTIFICIAL_TAGS_VALUE = "True";

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
   * Writes the attributes onto a span that is not yet finished, reading them from its {@code
   * _ml_obs_tag.} / {@code _ml_obs_metric.} tags. No-op for a span with no LLM Observability span
   * kind.
   */
  public static void apply(AgentSpan span) {
    if (span == null) {
      return;
    }
    String operation = stringTag(span, SPAN_KIND_TAG);
    if (operation == null) {
      return;
    }
    span.setTag(OPERATION_NAME, operation);

    boolean modelBacked =
        Tags.LLMOBS_LLM_SPAN_KIND.equals(operation)
            || Tags.LLMOBS_EMBEDDING_SPAN_KIND.equals(operation);

    String model = stringTag(span, MODEL_NAME_TAG);
    if (model != null || modelBacked) {
      span.setTag(REQUEST_MODEL, model == null ? DEFAULT_MODEL : model);
    }
    String provider = stringTag(span, MODEL_PROVIDER_TAG);
    if (provider != null || modelBacked) {
      span.setTag(
          PROVIDER_NAME, (provider == null ? DEFAULT_MODEL : provider).toLowerCase(Locale.ROOT));
    }
    String application = stringTag(span, ML_APP_TAG);
    if (application != null) {
      span.setTag(APPLICATION_NAME, application);
    }
    String sessionId = stringTag(span, SESSION_ID_TAG);
    if (sessionId != null) {
      span.setTag(CONVERSATION_ID, sessionId);
    }

    // Other kinds carry unrelated metrics that a gen_ai.usage.* key would misrepresent.
    if (modelBacked) {
      for (String[] metric : TOKEN_METRICS) {
        Object value = span.getTag(metric[0]);
        if (value instanceof Number) {
          span.setMetric(metric[1], ((Number) value).doubleValue());
        }
      }
    }

    span.setTag(ARTIFICIAL_TAGS, ARTIFICIAL_TAGS_VALUE);
  }

  /** The value of {@code key} as a non-empty string, or null. */
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
