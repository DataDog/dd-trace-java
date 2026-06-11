package datadog.trace.bootstrap.instrumentation.llm;

import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import jdk.jfr.Event;

/**
 * {@link LlmObsHandle} that drives a JFR {@link Event}, an {@link LLMObsSpan}, and an APM {@link
 * AgentSpan} independently. Any field may be null when the corresponding backend is disabled.
 */
public final class LlmCallHandle extends LlmObsHandle {

  private final Event jfrEvent;
  private final LLMObsSpan llmObsSpan;
  private final AgentScope agentScope;

  public LlmCallHandle(Event jfrEvent, LLMObsSpan llmObsSpan, AgentScope agentScope) {
    this.jfrEvent = jfrEvent;
    this.llmObsSpan = llmObsSpan;
    this.agentScope = agentScope;
  }

  @Override
  protected void onAsync() {
    if (jfrEvent != null) {
      jfrEvent.end();
      if (jfrEvent.shouldCommit()) {
        jfrEvent.commit();
      }
    }
    // Close the scope on the entering thread; the span is finished later in doFinish().
    if (agentScope != null) {
      agentScope.close();
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
    if (agentScope != null) {
      AgentSpan span = agentScope.span();
      if (hasError() && thrown() != null) {
        span.addThrowable(thrown());
      }
      span.finish();
      if (!isAsync()) {
        agentScope.close();
      }
    }
  }
}
