package com.datadog.iast.test

import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.SourceTypes
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.core.DDSpan


class IastAgentTestRunner extends InstrumentationSpecification implements IastRequestContextPreparationTrait {
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

  protected DDSpan runUnderIastTrace(Closure cl) {
    CallbackProvider iastCbp = TEST_TRACER.getCallbackProvider(RequestContextSlot.IAST)
    def reqStartCb = iastCbp.getCallback(Events.EVENTS.requestStarted())
    def reqEndCb = iastCbp.getCallback(Events.EVENTS.requestEnded())

    def iastCtx = reqStartCb.get().result
    def ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    AgentSpan span = TEST_TRACER.startSpan("test", "test-iast-span", ddctx)
    try {
      AgentTracer.activateSpan(span).withCloseable cl
    } finally {
      reqEndCb.apply(span.requestContext, span)
      span.finish()
    }

    span
  }
}
