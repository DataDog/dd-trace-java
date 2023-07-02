package com.datadog.iast.telemetry

import com.datadog.iast.IastRequestContext
import com.datadog.iast.telemetry.taint.TaintedObjectsWithTelemetry
import datadog.trace.api.gateway.Flow

class TelemetryRequestStartedHandlerTest extends AbstractTelemetryCallbackTest {

  void 'request started add the required collector'() {
    given:
    final handler = new TelemetryRequestStartedHandler(dependencies)

    when:
    final flow = handler.get()

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() instanceof IastRequestContext
    final iastCtx = flow.getResult() as IastRequestContext
    iastCtx.metricCollector != null
    iastCtx.taintedObjects instanceof TaintedObjectsWithTelemetry
    1 * dependencies.overheadController.acquireRequest() >> true
  }
}
