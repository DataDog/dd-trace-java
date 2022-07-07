package datadog.trace.api

import constructors.InaccessibleConstructor
import constructors.NoDefaultConstructor
import constructors.ThrowingConstructor
import datadog.trace.api.function.Function
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.ConcurrentHashMap

class FunctionsTest extends DDSpecification {

  def "test common string functions"() {
    when:
    CharSequence output = fn.apply(input)
    then:
    output == expected
    where:
    fn                               | input | expected
    Functions.LowerCase.INSTANCE     | "xYz" | "xyz"
    new Functions.ToString<String>() | "xYz" | "xYz"
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

  def "test encode UTF8" () {
    when:
    UTF8BytesString utf8 = Functions.UTF8_ENCODE.apply("foo")
    then:
    utf8.toString() == "foo"
  }

  def "test construct"() {
    when:
    Function<?, ConcurrentHashMap> func = Functions.newInstanceOf(ConcurrentHashMap)
    then:
    func.apply("") instanceof ConcurrentHashMap
  }

  def "test bad input"() {
    when: "no default constructor"
    Functions.newInstanceOf(NoDefaultConstructor)
    then:
    thrown IllegalStateException

    when: "inaccessible"
    Functions.newInstanceOf(InaccessibleConstructor)
    then:
    thrown IllegalStateException

    when: "will throw"
    def func = Functions.newInstanceOf(ThrowingConstructor)
    then:
    null == func.apply("")
  }
}
