import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;

import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v0")
class AzureFunctionsWorkerV0ForkedTest extends AzureFunctionsWorkerTest {

  @Override
  String operation() {
    return "dd-tracer-serverless-span";
  }
}
