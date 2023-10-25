package datadog.trace.instrumentation.jersey

import datadog.trace.agent.test.AgentTestRunner

class AbstractStringReaderTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }
}

