package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification

class GlobPatternTest extends DDSpecification {

  def "Convert glob pattern to regex"() {
    expect:
    GlobPattern.globToRegex(globPattern) == expectedRegex

    where:
    globPattern | expectedRegex
    "*"         | "^.*\$"
    "?"         | "^.\$"
    "??"        | "^..\$"
    "Foo*"      | "^Foo.*\$"
    "abc"       | "^abc\$"
    "?"         | "^.\$"
    "F?o"       | "^F.o\$"
    "Bar*"      | "^Bar.*\$"
  }
}
