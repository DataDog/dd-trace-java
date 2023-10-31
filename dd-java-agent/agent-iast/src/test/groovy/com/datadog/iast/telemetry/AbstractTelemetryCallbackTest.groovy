package com.datadog.iast.telemetry


import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.RequestEndedHandler
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.telemetry.IastMetricCollector
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

abstract class AbstractTelemetryCallbackTest extends IastModuleImplTestBase {

  protected TraceSegment traceSegment
  protected RequestEndedHandler delegate
  protected AgentSpan span
  protected RequestContext reqCtx
  protected IastRequestContext iastCtx
  protected IastMetricCollector globalCollector

  void setup() {
    InstrumentationBridge.clearIastModules()
    delegate = Spy(new RequestEndedHandler(dependencies))
    traceSegment = Mock(TraceSegment) {
    }
    iastCtx = new IastRequestContext(new IastMetricCollector())
    reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> iastCtx
      getTraceSegment() >> traceSegment
    }
    span = Mock(AgentSpan) {
      getRequestContext() >> reqCtx
    }
    globalCollector = IastMetricCollector.get()
    globalCollector.prepareMetrics()
    globalCollector.drain()
  }
}
