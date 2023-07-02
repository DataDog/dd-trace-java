package com.datadog.iast.telemetry


import com.datadog.iast.HasDependencies.Dependencies
import com.datadog.iast.IastModuleImplTestBase
import datadog.trace.api.Config
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

abstract class AbstractTelemetryCallbackTest extends IastModuleImplTestBase {

  protected AgentSpan span
  protected TraceSegment traceSegment
  protected Dependencies dependencies
  protected RequestContext reqCtx

  void setup() {
    dependencies = new Dependencies(
      Config.get(), reporter, overheadController, stackWalker
      )
    traceSegment = Mock(TraceSegment)
    reqCtx = Mock(RequestContext)
    reqCtx.getTraceSegment() >> traceSegment
    span = Mock(AgentSpan)
    span.getRequestContext() >> reqCtx
    tracer.activeSpan() >> span
  }
}
