package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification

class MatchersTest extends DDSpecification {

  def "match-all scenarios must return a null matcher"() {
    expect:
    Matchers.compileGlob(glob) == null

    where:
    glob << [null, "*"]
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
    matcher.matches(matching)
    !matcher.matches(nonMatching)

    where:
    pattern | matching | nonMatching
    "Fo?"   | "Foo"    | "Fooo"
    "Fo*"   | "Fo"     | "Fa"
    "F*B?r" | "FooBar" | "FooFar"
    ""      | ""       | "non-empty"
  }
}
