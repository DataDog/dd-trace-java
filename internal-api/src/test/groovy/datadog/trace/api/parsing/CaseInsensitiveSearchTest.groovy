package datadog.trace.api.parsing

import datadog.trace.test.util.DDSpecification

import java.nio.charset.StandardCharsets

class CaseInsensitiveSearchTest extends DDSpecification {


  def "find #term in #input at position #expected within [#from, #to)"() {
    when:
    CaseInsensitiveSearch search = new CaseInsensitiveSearch(term)
    int index = search.find(input.getBytes(StandardCharsets.UTF_8), from, to)

    then:
    index == expected

    where:
    term  | input     | from | to             | expected
    "foo" | "foo"     | 0    | input.length() | 0
    "foo" | "fOo"     | 0    | input.length() | 0
    "foo" | "foo"     | -1    | input.length() + 1 | 0
    "foo" | "fOo"     | -1    | input.length() + 1 | 0
    "foo" | "fOo"     | 1    | input.length() | -1
    "foo" | "BarFood" | 0    | input.length() | 3
    "foo" | "BarFood" | 4    | input.length() | -1
    "foo" | "barfood" | 0    | input.length() | 3
    "foo" | "barfood" | 4    | input.length() | -1
    "foo" | "bar"     | 0    | input.length() | -1
    "foo" | "BAR"     | 0    | input.length() | -1
    "foo" | "bark"    | 0    | input.length() | -1
    "foo" | "BARK"    | 0    | input.length() | -1
  }
}
