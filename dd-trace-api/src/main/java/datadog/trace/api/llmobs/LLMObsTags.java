package datadog.trace.api.llmobs;

// Well known tags for llm obs
public class LLMObsTags {
  public static final String ML_APP = "ml_app";
  public static final String SESSION_ID = "session_id";

  // meta
  public static final String METADATA = "metadata";

  // LLM spans related
  public static final String MODEL_NAME = "model_name";
  public static final String MODEL_VERSION = "model_version";
  public static final String MODEL_PROVIDER = "model_provider";
}
