package com.datadog.iast

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

class IastModuleImplTest extends DDSpecification {
  void 'iast module implementation'(){
    given:
    IastModuleImpl module = new IastModuleImpl()
    AgentSpan mockAgentSpan = Mock(AgentSpan)
    def mockCoreTracer = Mock(AgentTracer.TracerAPI)
    mockCoreTracer.activeSpan() >> mockAgentSpan
    AgentTracer.forceRegister(mockCoreTracer)


    when:
    module.onCipherAlgorithm("DES")
    module.onCipherAlgorithm("SHA-256")
    module.onHashingAlgorithm("MD5")
    module.onHashingAlgorithm("SHA-256")


    then:
    2* mockAgentSpan.getRequestContext()

    cleanup:
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER)
  }
}
