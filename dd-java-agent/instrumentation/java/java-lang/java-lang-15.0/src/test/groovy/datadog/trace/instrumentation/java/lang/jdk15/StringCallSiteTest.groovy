package datadog.trace.instrumentation.java.lang.jdk15

import com.github.javaparser.utils.StringEscapeUtils
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringJDK15Suite
import spock.lang.Requires

@Requires({
  jvm.java15Compatible
})
class StringCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test string translate escapes call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringJDK15Suite.stringTranslateEscapes(input)

    then:
    result == output
    1 * iastModule.onStringTranslateEscapes(input, output)

    where:
    input                   | output
    "HelloThisisaline"      | "HelloThisisaline"
    "Hello\tThis is a line" | "Hello"+ StringEscapeUtils.unescapeJava("\\u0009") +"This is a line"
    /Hello\sThis is a line/ | "Hello"+ StringEscapeUtils.unescapeJava("\\u0020") +"This is a line"
    /Hello\"This is a line/ | "Hello"+ StringEscapeUtils.unescapeJava("\\u0022") +"This is a line"
    /Hello\0This is a line/ | "Hello"+ StringEscapeUtils.unescapeJava("\\u0000") +"This is a line"
  }
}
