package datadog.trace.instrumentation.java.net

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestURLEncoderCallSiteSuite
import java.nio.charset.Charset

class URLEncoderCallSiteTest extends InstrumentationSpecification {
  // Explicit escape for non-ASCII `ståle` to make test independent of container settings.
  private static final String NON_ASCII_QUERY = 'my test.asp?name=st\u00E5le&car=saab'
  private static final String DEFAULT_CHARSET_ENCODED =
  URLEncoder.encode(NON_ASCII_QUERY, Charset.defaultCharset().name())

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
    args                       | expected
    [NON_ASCII_QUERY]          | DEFAULT_CHARSET_ENCODED
    [NON_ASCII_QUERY, 'UTF-8'] | 'my+test.asp%3Fname%3Dst%C3%A5le%26car%3Dsaab'
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
