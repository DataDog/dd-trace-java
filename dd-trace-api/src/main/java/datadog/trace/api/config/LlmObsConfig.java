package datadog.trace.api.config;

/**
 * Constant with names of configuration options for LLM Observability. (EXPERIMENTAL AND SUBJECT TO
 * CHANGE)
 */
public final class LlmObsConfig {

  public static final String LLMOBS_ENABLED = "llmobs.enabled";

  public static final String LLMOBS_ML_APP = "llmobs.ml.app";

  public static final String LLMOBS_AGENTLESS_ENABLED = "llmobs.agentless.enabled";

  private LlmObsConfig() {}
}
