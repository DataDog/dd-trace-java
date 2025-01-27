package datadog.trace.api.llmobs;

import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import javax.annotation.Nullable;

public class LLMObs {
  protected static LLMObsSpanFactory SPAN_FACTORY = NoOpLLMObsSpanFactory.INSTANCE;

  public static LLMObsSpan startLLMSpan(
      String spanName,
      String modelName,
      String modelProvider,
      @Nullable String mlApp,
      @Nullable String sessionID) {

    return SPAN_FACTORY.startLLMSpan(spanName, modelName, modelProvider, mlApp, sessionID);
  }

  public static LLMObsSpan startAgentSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startAgentSpan(spanName, mlApp, sessionID);
  }

  public static LLMObsSpan startToolSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startToolSpan(spanName, mlApp, sessionID);
  }

  public static LLMObsSpan startTaskSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startTaskSpan(spanName, mlApp, sessionID);
  }

  public static LLMObsSpan startWorkflowSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startWorkflowSpan(spanName, mlApp, sessionID);
  }

  public interface LLMObsSpanFactory {
    LLMObsSpan startLLMSpan(
        String spanName,
        String modelName,
        String modelProvider,
        @Nullable String mlApp,
        @Nullable String sessionID);

    LLMObsSpan startAgentSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);

    LLMObsSpan startToolSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);

    LLMObsSpan startTaskSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);

    LLMObsSpan startWorkflowSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionID);
  }
}
