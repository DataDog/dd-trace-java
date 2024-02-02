package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification

class MatchersTest extends DDSpecification {

  def "match-all scenarios must return an any matcher"() {
    expect:
    Matchers.compileGlob(glob) instanceof Matchers.AnyMatcher

    where:
    glob << [null, "*", "**"]
  }

  def "pattern without * or ? must be an EqualsMatcher"() {
    expect:
    Matchers.compileGlob(glob) instanceof Matchers.InsensitiveEqualsMatcher

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
    "fo?"   | "Foo"                    | true
    "Fo?"   | "Foo"                    | true
    "Fo?"   | new StringBuilder("Foo") | true
    "Fo?"   | new StringBuilder("foo") | true
    "Foo"   | new StringBuilder("foo") | true
    "bar"   | new StringBuilder("Baz") | false
    "Fo?"   | "Fooo"                   | false
    "Fo*"   | "Fo"                     | true
    "Fo*"   | "Fa"                     | false
    "F*B?r" | "FooBar"                 | true
    "F*B?r" | "FooFar"                 | false
    "f*b?r" | "FooBar"                 | true
    "*"     | true                     | true
    "true"  | true                     | true
    "false" | false                    | true
    "TRUE"  | true                     | true
    "FALSE" | false                    | true
    "True"  | true                     | true
    "False" | false                    | true
    "T*"    | true                     | true
    "F*"    | false                    | true
    ""      | ""                       | true
    ""      | "non-empty"              | false
    "*"     | "foo"                    | true
    "**"    | "foo"                    | true
    "???"   | "foo"                    | true
    "*"     | 20                       | true
    "20"    | 20                       | true
    "-20"   | -20                      | true
    "*"     | (byte)20                 | true
    "20"    | (byte)20                 | true
    "*"     | (short)20                | true
    "20"    | (short)20                | true
    "*"     | 20L                      | true
    "20"    | 20L                      | true
    "*"     | 20F                      | true
    "20"    | 20F                      | true
    "*"     | 20D                      | true
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
    "[a-z]" | "[a-z]"                  | true
    "[a-z]" | "a"                      | false
    "[abc]" | "[abc]"                  | true
    "[AbC]" | "[abc]"                  | true
    "[Ab]"  | new StringBuffer("[ab]") | true
    "[abc]" | "a"                      | false
    "[!ab]" | "[!ab]"                  | true
    "[!ab]" | "c"                      | false
    "^"     | "^"                      | true
    "()"    | "()"                     | true
    "(*)"   | "(-)"                    | true
    "\$"    | "\$"                     | true 
  }

  // helper functions - to subvert codenarc
  static bigInteger(str) {
    return new BigInteger(str)
  }

  static bigDecimal(str) {
    return new BigDecimal(str)
  }
}
