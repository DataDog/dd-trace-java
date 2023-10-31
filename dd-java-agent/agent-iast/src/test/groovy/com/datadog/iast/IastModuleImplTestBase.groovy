package com.datadog.iast

import com.datadog.iast.overhead.Operation
import com.datadog.iast.overhead.OverheadController
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.Config
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.stacktrace.StackWalker
import datadog.trace.util.stacktrace.StackWalkerFactory
import spock.lang.Shared

class IastModuleImplTestBase extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  protected Reporter reporter = Mock(Reporter)

  protected OverheadController overheadController = Mock(OverheadController)

  protected AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)

  // TODO replace by mock an fix all mock assertions (0 * _ will usually fail)
  protected StackWalker stackWalker = StackWalkerFactory.INSTANCE

  protected TaintedObjects taintedObjects

  protected Dependencies dependencies

  void setup() {
    overheadController.acquireRequest() >> true
    overheadController.consumeQuota(_ as Operation, _ as AgentSpan) >> true
    taintedObjects = TaintedObjects.build(1024)
    dependencies = new Dependencies(Config.get(), reporter, overheadController, stackWalker, taintedObjects)
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }
}
