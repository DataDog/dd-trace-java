package com.datadog.iast.test

import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.SourceTypes
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.core.DDSpan

import java.util.function.Supplier

class IastAgentTestRunner extends AgentTestRunner implements IastRequestContextPreparationTrait {
  public static final EMPTY_SOURCE = new Source(SourceTypes.NONE, '', '')

  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.iast.enabled', 'true')
  }

  protected Closure getRequestEndAction() { }

  void setupSpec() {
    TaintableVisitor.DEBUG = true
    iastSystemSetup(requestEndAction)
  }

  void cleanupSpec() {
    iastSystemCleanup()
  }

  protected TaintedObjects getLocalTaintedObjects() {
    IastContext.Provider.get().taintedObjects
  }

  protected TaintedObjectCollection getLocalTaintedObjectCollection() {
    new TaintedObjectCollection(localTaintedObjects)
  }

  protected TaintedObjectCollection getTaintedObjectCollection(DDSpan span) {
    final IastContext ctx = span.getRequestContext().getData(RequestContextSlot.IAST)
    return new TaintedObjectCollection(ctx.getTaintedObjects())
  }

  protected DDSpan runUnderIastTrace(Closure cl) {
    CallbackProvider iastCbp = TEST_TRACER.getCallbackProvider(RequestContextSlot.IAST)
    Supplier<Flow<Object>> reqStartCb = iastCbp.getCallback(Events.EVENTS.requestStarted())

    def iastCtx = reqStartCb.get().result
    def ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    AgentSpan span = TEST_TRACER.startSpan("test", "test-iast-span", ddctx)
    try {
      AgentTracer.activateSpan(span).withCloseable cl
    } finally {
      span.finish()
    }

    span
  }
}
