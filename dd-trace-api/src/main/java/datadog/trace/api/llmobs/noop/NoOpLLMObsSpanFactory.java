package datadog.trace.api.llmobs.noop;

import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import javax.annotation.Nullable;

public class NoOpLLMObsSpanFactory implements LLMObs.LLMObsSpanFactory {
  public static final NoOpLLMObsSpanFactory INSTANCE = new NoOpLLMObsSpanFactory();

  public LLMObsSpan startLLMSpan(
      String spanName,
      String modelName,
      String modelProvider,
      @Nullable String mlApp,
      @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }

  public LLMObsSpan startAgentSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }

  public LLMObsSpan startToolSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }

  public LLMObsSpan startTaskSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }

  public LLMObsSpan startWorkflowSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }

  public LLMObsSpan startEmbeddingSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }

  public LLMObsSpan startRetrievalSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }
}
