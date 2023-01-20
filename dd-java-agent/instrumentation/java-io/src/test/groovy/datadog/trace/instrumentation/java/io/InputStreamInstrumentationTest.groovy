package datadog.trace.instrumentation.java.io


import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.IOModule
import foo.bar.TestInputStreamSuite

class InputStreamInstrumentationTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test constructor with IS as arg()'() {
    setup:
    final ioModule = Mock(IOModule)
    InstrumentationBridge.registerIastModule(ioModule)
    final is = Mock(InputStream)

    when:
    TestInputStreamSuite.pushbackInputStreamFromIS(is)

    then:
    (1.._) * ioModule.onConstruct(is, _ as InputStream)
  }
}
