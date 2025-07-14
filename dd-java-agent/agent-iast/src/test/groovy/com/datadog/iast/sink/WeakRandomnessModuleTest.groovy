package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.overhead.Operations
import datadog.trace.api.iast.sink.WeakRandomnessModule

import java.security.SecureRandom

class WeakRandomnessModuleTest extends IastModuleImplTestBase {

  private WeakRandomnessModule module

  def setup() {
    module = new WeakRandomnessModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'test weak randomness detection'() {
    when:
    module.onWeakRandom(evidence)

    then: 'report is called with current active span'
    if (secure) {
      0 * _
    } else {
      tracer.activeSpan() >> span
      1 * reporter.report(span, _)
    }

    when:
    module.onWeakRandom(evidence)

    then: 'report is called with a new span if no active span'
    if (secure) {
      0 * _
    } else {
      tracer.activeSpan() >> null
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
    overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span, _ as VulnerabilityType) >> false
    0 * _
  }
}
