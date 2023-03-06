package datadog.trace.agent.test.naming

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig

abstract class VersionedNamingTestBase extends AgentTestRunner implements VersionedNamingTest {


  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, "v" + version())
  }
}
