package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification

class MatchersTest extends DDSpecification {

  def "match-all scenarios must return an any matcher"() {
    expect:
    Matchers.compileGlob(glob).isAny()

    where:
    glob << [null, "*", "**"]
  }

  def "pattern without * or ? must be an ExactMatcher"() {
    expect:
    Matchers.compileGlob(glob) instanceof Matchers.ExactMatcher

    where:
    glob << ["a", "ogre", "bcoho34e2"]
  }

  def "pattern with either * or ? must be a PatternMatcher"() {
    expect:
    Matchers.compileGlob(glob) instanceof Matchers.PatternMatcher

    where:
    glob << ["?", "foo*", "*bar", "F?oB?r", "F?o*", "?*", "*?"]
  }

  def "an exact matcher is self matching"() {
    expect:
    Matchers.compileGlob(pattern).matches(pattern)

    where:
    pattern << ["", "a", "abc", "cde"]
  }

  def "a pattern matcher test"() {
    when:
    def matcher = Matchers.compileGlob(pattern)

    then:
    matcher.matches(value) == matches

    where:
    pattern | value                    | matches
    "fo?"   | "Foo"                    | false
    "Fo?"   | "Foo"                    | true
    "Fo?"   | new StringBuilder("Foo") | true
    "Fo?"   | new StringBuilder("foo") | false
    "Fo?"   | "Fooo"                   | false
    "Fo*"   | "Fo"                     | true
    "Fo*"   | "Fa"                     | false
    "F*B?r" | "FooBar"                 | true
    "F*B?r" | "FooFar"                 | false
    "true"  | true                     | true
    "false" | false                    | true
    "TRUE"  | true                     | false
    "FALSE" | true                     | false
    ""      | ""                       | true
    ""      | "non-empty"              | false
    "*"     | "foo"                    | true
    "**"    | "foo"                    | true
    "???"   | "foo"                    | true
    "20"    | 20                       | true
    "-20"   | -20                      | true
    "20"    | (byte)(20)               | true
    "20"    | (short)(20)              | true
    "20"    | 20L                      | true
    "20"    | 20F                      | true
    "20"    | 20D                      | true
    "20"    | bigInteger("20")         | true
    "20"    | bigDecimal("20")         | true
    "2*"    | 20.1F                    | false
    "2*"    | 20.1D                    | false
    "2*"    | bigDecimal("20.1")       | false
    "*"     | new Object() {}          | true
    "**"    | new Object() {}          | true
    "?"     | new Object() {}          | false
    "*"     | null                     | true
    "?"     | null                     | false
  }

  // helper functions - to subvert codenarc
  static bigInteger(str) {
    return new BigInteger(str)
  }

  static bigDecimal(str) {
    return new BigDecimal(str)
  }
}
