package datadog.trace.api.llmobs;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// Well known tags for LLM obs will be prefixed with _llmobs_(tags|metrics).
public class LLMObsTags {
  // Prefix for tags
  public static final String LLMOBS_TAG_PREFIX = "_llmobs_tag.";
  // Prefix for metrics
  public static final String LLMOBS_METRIC_PREFIX = "_llmobs_metric.";

  public static final String ML_APP = "ml_app";
  public static final String INPUT = "input";
  public static final String OUTPUT = "output";
  public static final String SESSION_ID = "session_id";

  // meta
  public static final String METADATA = "metadata";

  // LLM spans related
  public static final String MODEL_NAME = "model_name";
  public static final String MODEL_VERSION = "model_version";
  public static final String MODEL_PROVIDER = "model_provider";

  public static final Set<String> SET = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      ML_APP, INPUT, OUTPUT, SESSION_ID, MODEL_NAME, MODEL_VERSION, MODEL_PROVIDER
  )));
}
