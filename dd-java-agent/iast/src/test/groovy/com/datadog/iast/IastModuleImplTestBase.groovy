package com.datadog.iast

import datadog.trace.api.Config
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class IastModuleImplTestBase extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  protected Reporter reporter = Mock(Reporter)

  protected IastModuleImpl module = new IastModuleImpl(Config.get(), reporter)

  protected AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)

  def setup() {
    AgentTracer.forceRegister(tracer)
  }

  def cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }
}
