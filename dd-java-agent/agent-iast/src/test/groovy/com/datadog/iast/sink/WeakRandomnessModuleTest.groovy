package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.overhead.Operations
import datadog.trace.api.iast.sink.WeakRandomnessModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import java.security.SecureRandom

class WeakRandomnessModuleTest extends IastModuleImplTestBase {

  private WeakRandomnessModule module

  private AgentSpan span

  def setup() {
    module = registerDependencies(new WeakRandomnessModuleImpl())
    span = Mock(AgentSpan) {
      getSpanId() >> 123456
    }
  }

  void 'test weak randomness detection'() {
    when:
    module.onWeakRandom(evidence)

    then: 'report is called with current active span'
    if (secure) {
      0 * _
    } else {
      tracer.activeSpan() >> span
      1 * overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span) >> true
      1 * reporter.report(span, _)
    }

    when:
    module.onWeakRandom(evidence)

    then: 'report is called with a new span if no active span'
    if (secure) {
      0 * _
    } else {
      tracer.activeSpan() >> null
      1 * overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, null) >> true
      1 * reporter.report(_, _)
    }

    where:
    evidence     | secure
    Random       | false
    SecureRandom | true
  }

  void 'test nothing is reported if no quota available'() {
    when:
    module.onWeakRandom(Random)

    then:
    tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span) >> false
    0 * _
  }
}
