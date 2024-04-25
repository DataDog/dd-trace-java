package iast

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import org.apache.kafka.common.utils.Utils

import java.nio.ByteBuffer

class UtilsInstrumentationForkedTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test toArray'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final buffer = args[0]

    when:
    final bytes = Utils.&"$method".call(args as Object[])

    then:
    bytes != null
    1 * propagationModule.taintObjectIfRangeTainted(_ as byte[], buffer, offset, length, false, VulnerabilityMarks.NOT_MARKED)
    0 * _

    where:
    method            | offset | length | args                                                    | _
    'toNullableArray' | 0      | 12     | [ByteBuffer.wrap('Hello World!'.bytes)]                 | _
    'toArray'         | 0      | 12     | [ByteBuffer.wrap('Hello World!'.bytes)]                 | _
    'toArray'         | 0      | 3      | [ByteBuffer.wrap('Hello World!'.bytes), length]         | _
    'toArray'         | 0      | 3      | [ByteBuffer.wrap('Hello World!'.bytes), offset, length] | _
    'toArray'         | 0      | 12     | [offHeap('Hello World!'.bytes)]                         | _
    'toArray'         | 0      | 3      | [offHeap('Hello World!'.bytes), length]                 | _
    'toArray'         | 0      | 3      | [offHeap('Hello World!'.bytes), offset, length]         | _
  }

  void 'test wrapNullable'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final bytes = 'Hello World!'.bytes

    when:
    final buffer = Utils.wrapNullable(bytes)

    then:
    buffer != null
    1 * propagationModule.taintObjectIfTainted(_ as ByteBuffer, bytes, true, VulnerabilityMarks.NOT_MARKED)
    0 * _
  }

  private static ByteBuffer offHeap(final byte[] bytes) {
    final buffer = ByteBuffer.allocateDirect(bytes.length)
    buffer.put(bytes)
    buffer.position(0)
    return buffer
  }
}
