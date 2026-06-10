package datadog.trace.bootstrap.instrumentation.llm;

import datadog.trace.api.llmobs.LLMObsSpan;
import java.util.Collections;
import jdk.jfr.Event;

/**
 * {@link LlmObsHandle} that drives a JFR {@link Event} and an {@link LLMObsSpan} independently.
 * Either field may be null when the corresponding backend is disabled.
 */
public final class LlmCallHandle extends LlmObsHandle {

  private final Event jfrEvent;
  private final LLMObsSpan llmObsSpan;

  public LlmCallHandle(Event jfrEvent, LLMObsSpan llmObsSpan) {
    this.jfrEvent = jfrEvent;
    this.llmObsSpan = llmObsSpan;
  }

  @Override
  protected void onAsync() {
    if (jfrEvent != null) {
      jfrEvent.end();
      if (jfrEvent.shouldCommit()) {
        jfrEvent.commit();
      }
    }
  }

  @Override
  protected void doFinish() {
    if (jfrEvent != null && !isAsync()) {
      jfrEvent.end();
      if (jfrEvent.shouldCommit()) {
        jfrEvent.commit();
      }
    }
    if (llmObsSpan != null) {
      if (hasError()) {
        llmObsSpan.setError(true);
        if (thrown() != null) {
          llmObsSpan.addThrowable(thrown());
        }
      }
      if (inputTokens() != null) {
        llmObsSpan.setMetric("input_tokens", inputTokens().intValue());
      }
      if (outputTokens() != null) {
        llmObsSpan.setMetric("output_tokens", outputTokens().intValue());
      }
      if (inputMessages() != null || outputMessages() != null) {
        llmObsSpan.annotateIO(
            inputMessages() != null ? inputMessages() : Collections.emptyList(),
            outputMessages() != null ? outputMessages() : Collections.emptyList());
      } else if (inputData() != null || outputData() != null) {
        llmObsSpan.annotateIO(inputData(), outputData());
      }
      llmObsSpan.finish();
    }
  }
}
