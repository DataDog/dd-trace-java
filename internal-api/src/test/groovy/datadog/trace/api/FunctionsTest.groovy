package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

class FunctionsTest extends DDSpecification {

  def "test common string functions"() {
    when:
    CharSequence output = fn.apply(input)
    then:
    output as String == expected
    where:
    fn                                   | input | expected
    Functions.LowerCase.INSTANCE         | "xYz" | "xyz"
    new Functions.ToString<String>()     | "xYz" | "xYz"
    new Functions.ToUTF8String<String>() | "xYz" | "xYz"
  }


  def "test join strings"() {
    when:
    CharSequence output = fn.apply(left, right)
    then:
    String.valueOf(output) == expected
    where:
    fn                                             | left | right | expected
    Functions.PrefixJoin.of("~", Functions.zero()) | "x"  | "y"   | "x~y"
    Functions.PrefixJoin.of("~")                   | "x"  | "y"   | "x~y"
    Functions.SuffixJoin.of("~", Functions.zero()) | "x"  | "y"   | "x~y"
    Functions.SuffixJoin.of("~")                   | "x"  | "y"   | "x~y"
  }

  def "test encode UTF8"() {
    when:
    UTF8BytesString utf8 = Functions.UTF8_ENCODE.apply("foo")
    then:
    utf8.toString() == "foo"
  }
}
