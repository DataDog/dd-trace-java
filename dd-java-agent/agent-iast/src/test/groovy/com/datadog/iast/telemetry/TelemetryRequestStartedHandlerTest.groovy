package com.datadog.iast.telemetry

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.telemetry.taint.TaintedObjectsWithTelemetry
import datadog.trace.api.gateway.Flow
import datadog.trace.api.iast.telemetry.Verbosity

class TelemetryRequestStartedHandlerTest extends IastModuleImplTestBase {

  void 'request started add the required collector'() {
    given:
    injectSysConfig('dd.iast.telemetry.verbosity', verbosity.name())
    final handler = new TelemetryRequestStartedHandler(dependencies)

    when:
    final flow = handler.get()

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() instanceof IastRequestContext
    final iastCtx = flow.getResult() as IastRequestContext
    iastCtx.metricCollector != null
    final withTelemetry = iastCtx.taintedObjects instanceof TaintedObjectsWithTelemetry
    withTelemetry == taintedObjectsWithTelemetry

    where:
    verbosity             | taintedObjectsWithTelemetry
    Verbosity.MANDATORY   | false
    Verbosity.INFORMATION | true
    Verbosity.DEBUG       | true
  }
}
