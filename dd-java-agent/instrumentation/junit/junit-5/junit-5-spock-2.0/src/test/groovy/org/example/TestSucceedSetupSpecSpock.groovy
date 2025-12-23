package org.example

import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Specification

class TestSucceedSetupSpecSpock extends Specification {

  def setupSpec() {
    AgentTracer.TracerAPI agentTracer = AgentTracer.get()
    AgentSpan span = agentTracer.buildSpan("spock-manual", "spec-setup").start()
    try (AgentScope scope = agentTracer.activateManualSpan(span)) {
      // manually trace spec setup to check whether ITR skips it or not
    }
    span.finish()
  }

  def "test success"() {
    expect:
    1 == 1
  }

  def "test another success"() {
    expect:
    1 == 1
  }
}
