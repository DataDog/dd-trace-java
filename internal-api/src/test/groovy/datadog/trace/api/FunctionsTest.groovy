package datadog.trace.api

import datadog.trace.util.test.DDSpecification

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
}
