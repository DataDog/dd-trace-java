package com.datadog.iast.telemetry.taint

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Range
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastMetricCollector
import datadog.trace.api.iast.telemetry.Verbosity

class TaintedObjectsWithTelemetryTest extends IastModuleImplTestBase {

  private IastMetricCollector mockCollector

  void setup() {
    mockCollector = Mock(IastMetricCollector)
    ctx.collector = mockCollector
  }

  void 'test request.tainted with #verbosity'() {
    given:
    final tainteds = [UUID.randomUUID(), UUID.randomUUID()]
    tainteds.each { ctx.taintedObjects.taint(it, [] as Range[])}
    ctx.taintedObjects = TaintedObjectsWithTelemetry.build(verbosity, ctx)

    when:
    ctx.taintedObjects.clear()

    then:
    if (IastMetric.REQUEST_TAINTED.isEnabled(verbosity)) {
      1 * mockCollector.addMetric(IastMetric.REQUEST_TAINTED, _, tainteds.size())
    } else {
      0 * mockCollector.addMetric
    }

    where:
    verbosity << Verbosity.values().toList()
  }

  void 'test executed.tainted with #verbosity'() {
    given:
    ctx.taintedObjects = TaintedObjectsWithTelemetry.build(verbosity, ctx)

    when:
    ctx.taintedObjects.taint('test', new Range[0])

    then:
    if (IastMetric.EXECUTED_TAINTED.isEnabled(verbosity)) {
      1 * mockCollector.addMetric(IastMetric.EXECUTED_TAINTED, _, 1)
    } else {
      0 * mockCollector.addMetric
    }

    where:
    verbosity << Verbosity.values().toList()
  }
}
