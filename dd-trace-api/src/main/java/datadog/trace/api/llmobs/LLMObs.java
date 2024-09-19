package datadog.trace.api.llmobs;

import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import javax.annotation.Nullable;

public class LLMObs {
  private static LLMObsSpanFactory SPAN_FACTORY = NoOpLLMObsSpanFactory.INSTANCE;

  /**
   * This a hook for injecting SpanFactory implementation. It should only be used internally by
   * the tracer logic
   *
   * @param spanFactory span factory instance
   */
  public static void registerSpanFactory(LLMObsSpanFactory spanFactory) {
    SPAN_FACTORY = spanFactory;
  }

  public static LLMObsSpan startLLMSpan(
      String spanName,String modelName,  String modelProvider, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startLLMSpan(spanName, modelName, modelProvider, sessionID, mlApp);
  }

  public static LLMObsSpan startAgentSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startAgentSpan(spanName, sessionID, mlApp);
  }

  public static LLMObsSpan startToolSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startToolSpan(spanName, sessionID, mlApp);
  }

  public static LLMObsSpan startTaskSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startTaskSpan(spanName, sessionID, mlApp);
  }

  public static LLMObsSpan startWorkflowSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startWorkflowSpan(spanName, sessionID, mlApp);
  }

  public interface LLMObsSpanFactory {
    LLMObsSpan startLLMSpan(String spanName, String modelName, String modelProvider, @Nullable String mlApp, @Nullable String sessionID);
    LLMObsSpan startAgentSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);
    LLMObsSpan startToolSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);
    LLMObsSpan startTaskSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);
    LLMObsSpan startWorkflowSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);
  }
}
