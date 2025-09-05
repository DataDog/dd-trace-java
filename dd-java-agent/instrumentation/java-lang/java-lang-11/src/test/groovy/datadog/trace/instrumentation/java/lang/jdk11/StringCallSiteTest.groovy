package datadog.trace.instrumentation.java.lang.jdk11

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringJDK11Suite
import spock.lang.Requires

@Requires({
  jvm.java11Compatible
})
class StringCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test string join call site'() {
    setup:
    final iastModule = Mock(StringModule)
    final self = 'abc'
    final count = 3
    final expected = 'abcabcabc'
    InstrumentationBridge.registerIastModule(iastModule)


    when:
    final result = TestStringJDK11Suite.stringRepeat(self, count)

    then:
    result == expected
    1 * iastModule.onStringRepeat(self, count, expected)
    0 * _
  }

  def 'test string #method call site'() {
    setup:
    final module = Mock(StringModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringJDK11Suite."$method"(input)

    then:
    result == output
    1 * module.onStringStrip(input, output, trailing)

    where:
    method                | trailing | input     | output
    "stringStrip"         | false    | ' hello ' | 'hello'
    "stringStripLeading"  | false    | ' hello ' | 'hello '
    "stringStripTrailing" | true     | ' hello ' | ' hello'
  }
}
