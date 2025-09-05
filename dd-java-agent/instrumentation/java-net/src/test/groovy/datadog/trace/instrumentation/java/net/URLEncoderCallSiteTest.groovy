package datadog.trace.instrumentation.java.net

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestURLEncoderCallSiteSuite

class URLEncoderCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test encode with args: #args'() {
    setup:
    final iastModule = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestURLEncoderCallSiteSuite.&encode.call(*args)

    then:
    result == expected
    1 * iastModule.onUrlEncode(args[0], args.size() == 1 ? null : args[1], _ as String)
    0 * _

    where:
    args                                         | expected
    ['my test.asp?name=ståle&car=saab']          | 'my+test.asp%3Fname%3Dst%C3%A5le%26car%3Dsaab'
    ['my test.asp?name=ståle&car=saab', 'UTF-8'] | 'my+test.asp%3Fname%3Dst%C3%A5le%26car%3Dsaab'
  }

  void 'test encode with null args'() {
    given:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    TestURLEncoderCallSiteSuite.&encode.call(*args)

    then:
    def ex = thrown(Exception)
    assert ex.stackTrace[0].getClassName().startsWith('java.net.')
    0 * _

    where:
    args         | _
    [null, null] | _
    [null]       | _
  }
}
