package com.datadog.iast

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

class IastModuleImplHashTest extends DDSpecification {

  void 'iast module vulnerable hash algorithm'(){
    given:
    IastModuleImpl module = new IastModuleImpl()
    AgentSpan mockAgentSpan = Mock(AgentSpan)
    def mockCoreTracer = Mock(AgentTracer.TracerAPI)
    mockCoreTracer.activeSpan() >> mockAgentSpan
    AgentTracer.forceRegister(mockCoreTracer)


    when:
    module.onHashingAlgorithm(algorithm)


    then:
    1 * mockAgentSpan.getRequestContext()

    cleanup:
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER)


    where:
    algorithm | _
    "MD2"     | _
    "MD5"     | _
    "md2"     | _
    "md5"     | _
    "RIPEMD128"     | _
    "MD4"     | _
  }

  void 'iast module called with null argument'(){
    given:
    IastModuleImpl module = new IastModuleImpl()
    AgentSpan mockAgentSpan = Mock(AgentSpan)
    def mockCoreTracer = Mock(AgentTracer.TracerAPI)
    mockCoreTracer.activeSpan() >> mockAgentSpan
    AgentTracer.forceRegister(mockCoreTracer)

    when:
    module.onHashingAlgorithm(null)


    then:
    noExceptionThrown()

    cleanup:
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER)
  }

  void 'iast module secure hash algorithm'(){
    given:
    IastModuleImpl module = new IastModuleImpl()
    AgentSpan mockAgentSpan = Mock(AgentSpan)
    def mockCoreTracer = Mock(AgentTracer.TracerAPI)
    mockCoreTracer.activeSpan() >> mockAgentSpan
    AgentTracer.forceRegister(mockCoreTracer)


    when:
    module.onHashingAlgorithm("SHA-256")


    then:
    0 * mockAgentSpan.getRequestContext()

    cleanup:
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER)
  }
}
