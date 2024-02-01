package datadog.trace.instrumentation.java.io

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestByteBufferSuite

import java.nio.ByteBuffer

class ByteBufferTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test wrap method'() {
    given:
    final message = 'Hello World!'.bytes
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestByteBufferSuite.wrap(message)

    then:
    1 * module.taintIfTainted(_ as ByteBuffer, message)
  }
}
