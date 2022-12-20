package com.datadog.iast

import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.core.DDSpan
import java.util.function.Supplier

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get

class IastAgentTestRunner extends AgentTestRunner {
  public static final EMPTY_SOURCE = new Source(SourceType.NONE, '', '')

  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void setupSpec() {
    // Register the Instrumentation Gateway callbacks
    def ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.IAST)
    IastSystem.start(ss, new NoopOverheadController())
  }

  void cleanupSpec() {
    get().getSubscriptionService(RequestContextSlot.IAST).reset()
    InstrumentationBridge.clearIastModules()
  }

  protected TaintedObjects getTaintedObjects() {
    IastRequestContext.get().taintedObjects
  }

  protected DDSpan runUnderIastTrace(Closure cl) {
    CallbackProvider iastCbp = TEST_TRACER.getCallbackProvider(RequestContextSlot.IAST)
    Supplier<Flow<Object>> reqStartCb = iastCbp.getCallback(Events.EVENTS.requestStarted())

    def iastCtx = reqStartCb.get().result
    def ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    AgentSpan span = TEST_TRACER.startSpan("test-iast-span", ddctx)
    try {
      AgentTracer.activateSpan(span).withCloseable cl
    } finally {
      span.finish()
    }

    span
  }
}
