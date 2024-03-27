package datadog.trace.instrumentation.java.io

import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestByteBufferSuite

import java.nio.ByteBuffer

class ByteBufferTest extends IastAgentTestRunner {

  void 'test wrap method'() {
    given:
    final message = 'Hello World!'.bytes
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace { TestByteBufferSuite.wrap(message) }

    then:
    1 * module.taintIfTainted(_ as IastContext, _ as ByteBuffer, message, true, VulnerabilityMarks.NOT_MARKED)
  }

  void 'test array method'() {
    given:
    final message = ByteBuffer.wrap('Hello World!'.bytes)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = computeUnderIastTrace { TestByteBufferSuite.array(message) }

    then:
    result == message.array()
    1 * module.taintIfTainted(_ as IastContext, _ as byte[], message, true, VulnerabilityMarks.NOT_MARKED)
  }
}
