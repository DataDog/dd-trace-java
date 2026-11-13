package datadog.trace.instrumentation.aws.v0;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.test.junit.utils.config.WithConfig;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class LegacyAdapterAwsContextRestorationTest extends AwsContextRestorationTest {
  @Override
  void configureContextManager() {
    super.configureContextManager();
    AgentTracer.installLegacyContextManager();
  }
}
