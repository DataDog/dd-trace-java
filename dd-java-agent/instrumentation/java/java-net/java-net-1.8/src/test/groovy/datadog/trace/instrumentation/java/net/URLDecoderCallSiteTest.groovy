package datadog.trace.instrumentation.java.net

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import foo.bar.TestURLDecoderCallSiteSuite
import datadog.trace.agent.test.InstrumentationSpecification

class URLDecoderCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test decode'() {
    setup:
    final iastModule = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestURLDecoderCallSiteSuite.decode('test')

    then:
    result == 'test'
    1 * iastModule.onUrlDecode('test', null, 'test')
    0 * _

    when:
    TestURLDecoderCallSiteSuite.decode(null)

    then:
    thrown(NullPointerException)
    0 * _
  }

  def 'test decode with encoding'() {
    setup:
    final iastModule = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestURLDecoderCallSiteSuite.decode('test', 'utf-8')

    then:
    result == 'test'
    1 * iastModule.onUrlDecode('test', 'utf-8', 'test')
    0 * _

    when:
    TestURLDecoderCallSiteSuite.decode(null, 'utf-8')

    then:
    thrown(NullPointerException)
    0 * _
  }
}
