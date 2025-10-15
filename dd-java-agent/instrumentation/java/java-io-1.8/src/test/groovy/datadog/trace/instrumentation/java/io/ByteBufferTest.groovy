package datadog.trace.instrumentation.java.io

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestByteBufferSuite

import java.nio.ByteBuffer

class ByteBufferTest extends InstrumentationSpecification {

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
    1 * module.taintObjectIfTainted(_ as ByteBuffer, message, true, VulnerabilityMarks.NOT_MARKED)
  }

  void 'test array method'() {
    given:
    final message = ByteBuffer.wrap('Hello World!'.bytes)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestByteBufferSuite.array(message)

    then:
    result == message.array()
    1 * module.taintObjectIfTainted(_ as byte[], message, true, VulnerabilityMarks.NOT_MARKED)
  }
}
