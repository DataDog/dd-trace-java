package datadog.trace.api.sampling

import spock.lang.Specification

class SamplingRuleTest extends Specification {

  def "test normalizeGlob"() {
    expect:
    SamplingRule.normalizeGlob(glob) == normalized

    where:
    glob  | normalized
    null  | SamplingRule.MATCH_ALL
    "*"   | SamplingRule.MATCH_ALL
    "**"  | "**"
    "a"   | "a"
    "a*"  | "a*"
    "a**" | "a**"
    "a*b" | "a*b"
    ""    | ""
  }
}
