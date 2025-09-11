package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.IastConfig
import datadog.trace.api.iast.IastDetectionMode
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringSuite

import java.nio.charset.Charset

class StringFullDetectionCallSiteForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
    injectSysConfig(IastConfig.IAST_DETECTION_MODE, IastDetectionMode.FULL.name())
  }

  void 'test get bytes'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final byte[] bytes = TestStringSuite.&"$method".call(args as Object[])

    then:
    bytes != null && bytes.length > 0
    1 * module.onStringGetBytes(args[0] as String, charset, _ as byte[])

    where:
    method     | charset                         | args
    'getBytes' | null                            | ['Hello']
    'getBytes' | 'UTF-8'                         | ['Hello', charset]
    'getBytes' | Charset.defaultCharset().name() | ['Hello', Charset.forName(charset)]
  }

  void 'test string constructor with byte array'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringSuite.&stringConstructor.call(args as Object[])

    then:
    result != null && !result.empty
    1 * module.onStringFromBytes(args[0] as byte[], offset, length, charset, _ as String)

    where:
    charset                         | offset | length | args
    null                            | 0      | 5      | ['Hello'.bytes]
    null                            | 0      | 5      | ['Hello'.bytes, offset, length]
    'UTF-8'                         | 0      | 5      | ['Hello'.getBytes(charset), charset]
    'UTF-8'                         | 0      | 2      | ['Hello'.getBytes(charset), offset, length, charset]
    Charset.defaultCharset().name() | 0      | 5      | ['Hello'.getBytes(charset), Charset.forName(charset)]
    Charset.defaultCharset().name() | 0      | 2      | ['Hello'.getBytes(charset), offset, length, Charset.forName(charset)]
  }

  void 'test string toCharArray'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final string = 'test'

    when:
    final char[] result = TestStringSuite.toCharArray(string)

    then:
    result != null && result.length > 0
    1 * module.taintObjectIfTainted(_ as char[], string, true, VulnerabilityMarks.NOT_MARKED)
    0 * _
  }

  void 'test get bytes'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final byte[] bytes = TestStringSuite.&"$method".call(args as Object[])

    then:
    bytes != null && bytes.length > 0
    1 * module.onStringGetBytes(args[0] as String, charset, _ as byte[])

    where:
    method     | charset                         | args
    'getBytes' | null                            | ['Hello']
    'getBytes' | 'UTF-8'                         | ['Hello', charset]
    'getBytes' | Charset.defaultCharset().name() | ['Hello', Charset.forName(charset)]
  }

  void 'test string constructor with byte array'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringSuite.&stringConstructor.call(args as Object[])

    then:
    result != null && !result.empty
    1 * module.onStringFromBytes(args[0] as byte[], offset, length, charset, _ as String)

    where:
    charset                         | offset | length | args
    null                            | 0      | 5      | ['Hello'.bytes]
    null                            | 0      | 5      | ['Hello'.bytes, offset, length]
    'UTF-8'                         | 0      | 5      | ['Hello'.getBytes(charset), charset]
    'UTF-8'                         | 0      | 2      | ['Hello'.getBytes(charset), offset, length, charset]
    Charset.defaultCharset().name() | 0      | 5      | ['Hello'.getBytes(charset), Charset.forName(charset)]
    Charset.defaultCharset().name() | 0      | 2      | ['Hello'.getBytes(charset), offset, length, Charset.forName(charset)]
  }
}
