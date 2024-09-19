package datadog.trace.api.llmobs;

import datadog.trace.api.llmobs.noop.NoOpLLMObsSpan;
import javax.annotation.Nullable;

public class LLMObs {
  public static LLMObsSpan startLLMSpan(
      String modelName, String name, @Nullable String modelProvider, @Nullable String sessionID, @Nullable String mlApp) {

    return NoOpLLMObsSpan.INSTANCE;
  }

  public static LLMObsSpan startAgentSpan(
      String name, @Nullable String sessionID, @Nullable String mlApp) {

    return NoOpLLMObsSpan.INSTANCE;
  }

  public static LLMObsSpan startToolSpan(
      String name, @Nullable String sessionID, @Nullable String mlApp) {

    return NoOpLLMObsSpan.INSTANCE;
  }

  public static LLMObsSpan startTaskSpan(
      String name, @Nullable String sessionID, @Nullable String mlApp) {

    return NoOpLLMObsSpan.INSTANCE;
  }

  public static LLMObsSpan startWorkflowSpan(
      String name, @Nullable String sessionID, @Nullable String mlApp) {

    return NoOpLLMObsSpan.INSTANCE;
  }
}
