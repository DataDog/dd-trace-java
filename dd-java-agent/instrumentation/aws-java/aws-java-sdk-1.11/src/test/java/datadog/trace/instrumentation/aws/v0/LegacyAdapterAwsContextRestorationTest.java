package datadog.trace.instrumentation.aws.v0;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

class LegacyAdapterAwsContextRestorationTest extends AwsContextRestorationTest {
  @Override
  void configureContextManager() {
    super.configureContextManager();
    AgentTracer.installLegacyContextManager();
  }
}
