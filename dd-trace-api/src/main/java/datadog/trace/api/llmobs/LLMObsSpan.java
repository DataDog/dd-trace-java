package datadog.trace.api.llmobs;

import datadog.trace.api.DDTraceId;
import java.util.List;
import java.util.Map;

/** This interface represent an individual LLM Obs span. */
public interface LLMObsSpan {

  /**
   * Annotate the span with inputs and outputs for LLM spans
   *
   * @param inputMessages The input messages of the span in the form of a list
   * @param outputMessages The output messages of the span in the form of a list
   */
  void annotateIO(List<LLMObs.LLMMessage> inputMessages, List<LLMObs.LLMMessage> outputMessages);

  /**
   * Annotate an embedding span with document inputs and a string output.
   *
   * @param inputDocuments The input documents of the span
   * @param outputData The output data of the span in the form of a string
   */
  default void annotateEmbeddingIO(List<LLMObs.Document> inputDocuments, String outputData) {
    annotateIO((String) null, outputData);
  }

  /**
   * Annotate a retrieval span with a string input and document outputs.
   *
   * @param inputData The input data of the span in the form of a string
   * @param outputDocuments The output documents of the span
   */
  default void annotateRetrievalIO(String inputData, List<LLMObs.Document> outputDocuments) {
    annotateIO(inputData, (String) null);
  }

  /**
   * Annotate the span with inputs and outputs
   *
   * @param inputData The input data of the span in the form of a string
   * @param outputData The output data of the span in the form of a string
   */
  void annotateIO(String inputData, String outputData);

  /**
   * Annotate an LLM span with the prompt used for the LLM call.
   *
   * <p>This annotation is ignored for non-LLM spans.
   *
   * @param prompt The prompt used for the LLM call
   */
  default void annotatePrompt(LLMObs.Prompt prompt) {}

  /**
   * Annotate the span with the definitions of tools available to the LLM.
   *
   * @param toolDefinitions The tool definitions supplied to the LLM
   */
  default void setToolDefinitions(List<LLMObs.ToolDefinition> toolDefinitions) {}

  /**
   * Annotate an agent span with its manifest configuration.
   *
   * <p>This annotation is ignored for non-agent spans.
   *
   * @param agentManifest The agent manifest configuration
   */
  default void annotateAgentManifest(LLMObs.AgentManifest agentManifest) {}

  /**
   * Annotate the span with metadata
   *
   * @param metadata A map of JSON serializable key-value pairs that contains metadata information
   *     relevant to the input or output operation described by the span
   */
  void setMetadata(Map<String, Object> metadata);

  /**
   * Annotate the span with metrics
   *
   * @param metrics A map of JSON serializable keys and numeric values that users can add as metrics
   *     relevant to the operation described by the span (input_tokens, output_tokens, total_tokens,
   *     etc.).
   */
  void setMetrics(Map<String, Number> metrics);

  /**
   * Annotate the span with a single metric key value pair for the span’s context (number of tokens
   * document length, etc).
   *
   * @param key the name of the metric
   * @param value the value of the metric
   */
  void setMetric(CharSequence key, int value);

  /**
   * Annotate the span with a single metric key value pair for the span’s context (number of tokens
   * document length, etc).
   *
   * @param key the name of the metric
   * @param value the value of the metric
   */
  void setMetric(CharSequence key, long value);

  /**
   * Annotate the span with a single metric key value pair for the span’s context (number of tokens
   * document length, etc).
   *
   * @param key the name of the metric
   * @param value the value of the metric
   */
  void setMetric(CharSequence key, double value);

  /**
   * Annotate the span with tags
   *
   * @param tags An map of JSON serializable key-value pairs that users can add as tags regarding
   *     the span’s context (session, environment, system, versioning, etc.).
   */
  void setTags(Map<String, Object> tags);

  /**
   * Annotate the span with a single tag key value pair as a tag regarding the span’s context
   * (session, environment, system, versioning, etc.).
   *
   * @param key the key of the tag
   * @param value the value of the tag
   */
  void setTag(String key, String value);

  /**
   * Annotate the span with a single tag key value pair as a tag regarding the span’s context
   * (session, environment, system, versioning, etc.).
   *
   * @param key the key of the tag
   * @param value the value of the tag
   */
  void setTag(String key, boolean value);

  /**
   * Annotate the span with a single tag key value pair as a tag regarding the span’s context
   * (session, environment, system, versioning, etc.).
   *
   * @param key the key of the tag
   * @param value the value of the tag
   */
  void setTag(String key, int value);

  /**
   * Annotate the span with a single tag key value pair as a tag regarding the span’s context
   * (session, environment, system, versioning, etc.).
   *
   * @param key the key of the tag
   * @param value the value of the tag
   */
  void setTag(String key, long value);

  /**
   * Annotate the span with a single tag key value pair as a tag regarding the span’s context
   * (session, environment, system, versioning, etc.).
   *
   * @param key the key of the tag
   * @param value the value of the tag
   */
  void setTag(String key, double value);

  /**
   * Annotate the span to indicate that an error occurred
   *
   * @param error whether an error occurred
   */
  void setError(boolean error);

  /**
   * Annotate the span with an error message
   *
   * @param errorMessage the message of the error
   */
  void setErrorMessage(String errorMessage);

  /**
   * Annotate the span with a throwable
   *
   * @param throwable the errored throwable
   */
  void addThrowable(Throwable throwable);

  /** Finishes (closes) a span */
  void finish();

  /**
   * Gets the TraceId of the span's trace.
   *
   * @return The TraceId of the span's trace, or {@link DDTraceId#ZERO} if not set.
   */
  DDTraceId getTraceId();

  /**
   * Gets the SpanId.
   *
   * @return The span identifier.
   */
  long getSpanId();
}
