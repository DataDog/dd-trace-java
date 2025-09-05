package datadog.trace.agent.test.naming

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TracerConfig

abstract class VersionedNamingTestBase extends InstrumentationSpecification implements VersionedNamingTest {


  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, "v" + version())
  }
}
