package datadog.trace.api.llmobs;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Mutable view of an LLM Observability span passed to a registered {@link LLMObsSpanProcessor}.
 *
 * <p>Changes to the input and output are applied immediately before the span is sent to LLM
 * Observability.
 */
public interface LLMObsSpanData {

  /**
   * Gets the LLM Observability span kind.
   *
   * @return the span kind
   */
  String getKind();

  /**
   * Gets the input content associated with the span.
   *
   * @return the input represented as messages
   */
  List<LLMObs.LLMMessage> getInput();

  /**
   * Replaces the input content associated with the span.
   *
   * @param input the new input represented as messages
   * @throws NullPointerException if {@code input} is {@code null}
   */
  void setInput(List<LLMObs.LLMMessage> input);

  /**
   * Gets the output content associated with the span.
   *
   * @return the output represented as messages
   */
  List<LLMObs.LLMMessage> getOutput();

  /**
   * Replaces the output content associated with the span.
   *
   * @param output the new output represented as messages
   * @throws NullPointerException if {@code output} is {@code null}
   */
  void setOutput(List<LLMObs.LLMMessage> output);

  /**
   * Gets an LLM Observability tag from the span.
   *
   * @param key the unprefixed tag name
   * @return the tag value, or {@code null} when the tag is not present
   */
  @Nullable
  String getTag(String key);
}
