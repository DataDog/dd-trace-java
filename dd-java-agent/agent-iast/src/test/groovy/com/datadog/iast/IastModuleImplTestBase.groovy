package com.datadog.iast

import com.datadog.iast.overhead.Operation
import com.datadog.iast.overhead.OverheadController
import datadog.trace.api.Config
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.stacktrace.StackWalker
import datadog.trace.util.stacktrace.StackWalkerFactory
import spock.lang.Shared

class IastModuleImplTestBase extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  protected IastRequestContext ctx

  protected TraceSegment traceSegment

  protected RequestContext reqCtx

  protected AgentSpan span

  protected AgentTracer.TracerAPI tracer

  protected Reporter reporter

  protected OverheadController overheadController

  protected StackWalker stackWalker

  protected Dependencies dependencies

  protected List<Object> objectHolder

  void setup() {
    ctx = buildIastRequestContext()
    traceSegment = buildTraceSegment()
    reqCtx = buildRequestContext()
    span = buildAgentSpan()
    tracer = buildAgentTracer()
    reporter = buildReporter()
    overheadController = buildOverheadController()
    stackWalker = StackWalkerFactory.INSTANCE

    dependencies = new Dependencies(Config.get(), reporter, overheadController, stackWalker)
    objectHolder = []

    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  protected IastRequestContext buildIastRequestContext() {
    return new IastRequestContext()
  }

  protected TraceSegment buildTraceSegment() {
    return Stub(TraceSegment)
  }

  protected RequestContext buildRequestContext() {
    return Stub(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
      getTraceSegment() >> traceSegment
    }
  }

  protected AgentSpan buildAgentSpan() {
    return Stub(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
  }

  protected AgentTracer.TracerAPI buildAgentTracer() {
    return Stub(AgentTracer.TracerAPI) {
      activeSpan() >> span
      getTraceSegment() >> traceSegment
    }
  }

  protected Reporter buildReporter() {
    return Stub(Reporter)
  }

  protected OverheadController buildOverheadController() {
    return Stub(OverheadController) {
      acquireRequest() >> true
      consumeQuota(_ as Operation, _) >> true
    }
  }
}
