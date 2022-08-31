package com.datadog.iast

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

class IastModuleImplCipherTest extends DDSpecification {

  void 'iast module vulnerable cipher algorithm'(){
    given:
    IastModuleImpl module = new IastModuleImpl()
    AgentSpan mockAgentSpan = Mock(AgentSpan)
    def mockCoreTracer = Mock(AgentTracer.TracerAPI)
    mockCoreTracer.activeSpan() >> mockAgentSpan
    AgentTracer.forceRegister(mockCoreTracer)


    when:
    module.onCipherAlgorithm(algorithm)

    then:
    1* mockAgentSpan.getRequestContext()

    cleanup:
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER)

    where:
    algorithm | _
    "DES" | _
    "DESede" | _
    "PBEWithMD5AndDES" | _
    "PBEWithMD5AndTripleDES" | _
    "PBEWithSHA1AndDESede" | _
    "PBEWithHmacSHA1AndAES_128" | _
    "PBEWithHmacSHA224AndAES_128" | _
    "PBEWithHmacSHA256AndAES_128" | _
    "PBEWithHmacSHA384AndAES_128" | _
    "PBEWithHmacSHA512AndAES_128" | _
    "PBEWithHmacSHA1AndAES_256" | _
    "PBEWithHmacSHA224AndAES_256" | _
    "PBEWithHmacSHA256AndAES_256" | _
    "PBEWithHmacSHA384AndAES_256" | _
    "PBEWithHmacSHA512AndAES_256" | _
    "RC2" | _
    "Blowfish" | _
    "ARCFOUR" | _
    "DESedeWrap" | _
    "PBEWithSHA1AndRC2_128" | _
    "PBEWithSHA1AndRC4_40" | _
    "PBEWithSHA1AndRC4_128" | _
    "PBEWithHmacSHA1AndAES_128" | _
  }

  void 'iast module called with null argument'(){
    given:
    IastModuleImpl module = new IastModuleImpl()
    AgentSpan mockAgentSpan = Mock(AgentSpan)
    def mockCoreTracer = Mock(AgentTracer.TracerAPI)
    mockCoreTracer.activeSpan() >> mockAgentSpan
    AgentTracer.forceRegister(mockCoreTracer)

    when:
    module.onCipherAlgorithm(null)


    then:
    noExceptionThrown()

    cleanup:
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER)
  }

  void 'iast module not blocklisted cipher algorithm'(){
    given:
    IastModuleImpl module = new IastModuleImpl()
    AgentSpan mockAgentSpan = Mock(AgentSpan)
    def mockCoreTracer = Mock(AgentTracer.TracerAPI)
    mockCoreTracer.activeSpan() >> mockAgentSpan
    AgentTracer.forceRegister(mockCoreTracer)

    when:
    module.onCipherAlgorithm("SecureAlgorithm")


    then:
    0 * mockAgentSpan.getRequestContext()

    cleanup:
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER)
  }
}
