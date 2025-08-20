package datadog.trace.api.llmobs.noop;

import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoOpLLMObsSpanFactory implements LLMObs.LLMObsSpanFactory {
  public static final NoOpLLMObsSpanFactory INSTANCE = new NoOpLLMObsSpanFactory();

  private static final Logger LOGGER = LoggerFactory.getLogger(NoOpLLMObsSpanFactory.class);

  public LLMObsSpan startLLMSpan(
      String spanName,
      String modelName,
      String modelProvider,
      @Nullable String mlApp,
      @Nullable String sessionId) {

    LOGGER.debug("LLM OBS STARTED NOOP LLM SPAN");

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
      String spanName,
      @Nullable String mlApp,
      @Nullable String modelProvider,
      @Nullable String modelName,
      @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }

  public LLMObsSpan startRetrievalSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return NoOpLLMObsSpan.INSTANCE;
  }
}
