package datadog.trace.instrumentation.java.io

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UntrustedDeserializationModule
import foo.bar.TestObjectInputStreamSuite

class ObjectInputStreamCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test onInputStream'() {
    setup:
    final module = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(module)

    final InputStream inputStream = new ByteArrayInputStream(new byte[0])

    when:
    TestObjectInputStreamSuite.init(inputStream)

    then:
    1 * module.onInputStream(_)
  }
}
