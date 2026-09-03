package datadog.trace.api.llmobs;

// Well known tags for llm obs
public class LLMObsTags {
  public static final String ML_APP = "ml_app";
  public static final String SESSION_ID = "session_id";
  public static final String AGENT_VERSION = "agent_version";

  // meta
  public static final String METADATA = "metadata";

  // LLM spans related
  public static final String MODEL_NAME = "model_name";
  public static final String MODEL_VERSION = "model_version";
  public static final String MODEL_PROVIDER = "model_provider";
  public static final String TOOL_DEFINITIONS = "tool_definitions";
  public static final String AGENT_MANIFEST = "agent_manifest";

  // Agent attribution
  public static final String PAGENT_SPAN_ID = "pagent_span_id";
  public static final String PAGENT_NAME = "pagent_name";

  // Distributed tracing propagation tags. These ride alongside the standard APM `x-datadog-tags`
  // propagating tags so a mixed-language pipeline can still join an LLMObs trace across a
  // process boundary that isn't covered by automatic instrumentation (e.g. an SQS worker).
  // Naming matches the `_dd.p.llmobs_*` convention used by dd-trace-py/js/go.
  public static final String PROPAGATED_ML_APP = "_dd.p.llmobs_ml_app";
  public static final String PROPAGATED_SESSION_ID = "_dd.p.llmobs_sid";
  public static final String PROPAGATED_PAGENT_SPAN_ID = "_dd.p.llmobs_pagent_span_id";
  public static final String PROPAGATED_PAGENT_NAME = "_dd.p.llmobs_pagent_name";
}
