package datadog.trace.api.llmobs;

/**
 * This interface represent an individual LLM Obs span.
 */
public interface LLMObsSpan {

  /**
   * Annotate spans with inputs, outputs, and metadata.
   *
   * @param key The name of the tag
   * @param value The value of the tag
   */
  void annotate(String key, Object value);
}
