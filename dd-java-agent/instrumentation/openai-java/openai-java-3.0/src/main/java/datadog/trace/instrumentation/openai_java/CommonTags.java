package datadog.trace.instrumentation.openai_java;

import datadog.trace.api.llmobs.LLMObsTags;

interface CommonTags {
  String OPENAI_API_BASE = "openai.api_base";
  String OPENAI_ORGANIZATION = "openai.organization";
  String OPENAI_REQUEST_ENDPOINT = "openai.request.endpoint";
  String OPENAI_REQUEST_METHOD = "openai.request.method";
  String OPENAI_REQUEST_MODEL = "openai.request.model";
  String OPENAI_RESPONSE_MODEL = "openai.response.model";

  String TAG_PREFIX = "_ml_obs_tag.";
  String SPAN_KIND = TAG_PREFIX + "span.kind";
  String INPUT = TAG_PREFIX + "input";
  String OUTPUT = TAG_PREFIX + "output";
  String METADATA = TAG_PREFIX + LLMObsTags.METADATA;
  String MODEL_NAME = TAG_PREFIX + LLMObsTags.MODEL_NAME;
  String MODEL_PROVIDER = TAG_PREFIX + LLMObsTags.MODEL_PROVIDER;

  String ML_APP = TAG_PREFIX + LLMObsTags.ML_APP;
  String VERSION = TAG_PREFIX + "version";

  String ENV = TAG_PREFIX + "env";
  String SERVICE = TAG_PREFIX + "service";
  String PARENT_ID = TAG_PREFIX + "parent_id";

  String METRIC_PREFIX = "_ml_obs_metric.";
  String INPUT_TOKENS = METRIC_PREFIX + "input_tokens";
  String OUTPUT_TOKENS = METRIC_PREFIX + "output_tokens";
  String TOTAL_TOKENS = METRIC_PREFIX + "total_tokens";
  String REASONING_OUTPUT_TOKENS = METRIC_PREFIX + "reasoning_output_tokens";
  String CACHE_READ_INPUT_TOKENS = METRIC_PREFIX + "cache_read_input_tokens";

  String REQUEST_REASONING = "_ml_obs_request.reasoning";
}
