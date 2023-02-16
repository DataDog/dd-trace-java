package datadog.trace.agent.test.naming

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig

abstract class VersionedNamingTestBase extends AgentTestRunner {
  protected abstract int version()
  protected abstract String service()
  protected abstract String operation()

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, "v" + version())
  }
}
