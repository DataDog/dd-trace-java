package com.datadog.iast

import com.datadog.iast.overhead.Operation
import com.datadog.iast.overhead.OverheadController
import datadog.trace.api.Config
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class IastModuleImplTestBase extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  protected Reporter reporter = Mock(Reporter)

  protected OverheadController overheadController = Mock(OverheadController)

  protected IastModuleImpl module = new IastModuleImpl(Config.get(), reporter, overheadController)

  protected AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)

  def setup() {
    AgentTracer.forceRegister(tracer)
    overheadController.acquireRequest() >> true
    overheadController.consumeQuota(_ as Operation, _ as AgentSpan) >> true
  }

  def cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }
}
