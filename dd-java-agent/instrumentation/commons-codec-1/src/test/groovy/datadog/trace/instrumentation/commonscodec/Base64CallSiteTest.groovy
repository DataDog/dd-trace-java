package datadog.trace.instrumentation.commonscodec

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import foo.bar.TestBase64CallSiteSuite
import groovy.transform.CompileDynamic
import org.apache.commons.codec.binary.Base64

@CompileDynamic
class Base64CallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test encode base 64 #iterationIndex'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final byte[] result = TestBase64CallSiteSuite.&"$method".call(args.toArray())

    then:
    result != null && result.length > 0
    1 * module.onBase64Encode(args.first() as byte[], _ as byte[])

    where:
    method   | args
    'encode' | ['Hello'.bytes]
    'encode' | ['Hello'.bytes, new Base64()]
  }

  void 'test decode base 64 #iterationIndex'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final byte[] result = TestBase64CallSiteSuite.&"$method".call(args.toArray())

    then:
    result != null && result.length > 0
    1 * module.onBase64Decode(args.first() as byte[], _ as byte[])

    where:
    method   | args
    'decode' | [Base64.encodeBase64('Hello'.bytes)]
    'decode' | [Base64.encodeBase64('Hello'.bytes), new Base64()]
  }
}
