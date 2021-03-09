package datadog.trace.api.parsing

import datadog.trace.test.util.DDSpecification

import java.nio.charset.StandardCharsets

class IndexesOfSearchTest extends DDSpecification {


  def "find indexes"() {
    when:
    IndexesOfSearch search = new IndexesOfSearch(term.getBytes(StandardCharsets.US_ASCII))
    BitSet positions = search.indexesIn(input.getBytes(StandardCharsets.UTF_8))
    then:
    if (expected.isEmpty()) {
      assert positions == null
    } else {
      positions.cardinality() == expected.size()
      for (Integer i : expected) {
        assert positions.get(i)
      }
    }

    where:
    term | input                | expected
    "xy" | "xyz"                | [0, 1]
    "xy" | "xyzxyzxyz"          | [0, 1, 3, 4, 6, 7]
    "xy" | "xyzxyzxyzxyzxyzxyz" | [0, 1, 3, 4, 6, 7, 9, 10, 12, 13, 15, 16]
    "x"  | "xxxxyyyyxxxxyyxxy"  | [0, 1, 2, 3, 8, 9, 10, 11, 14, 15]
    "x"  | "yy"                 | []
    "x"  | "yyyy"               | []
    "x"  | "yyyyyyyy"           | []
    "x"  | "yyyyyyyyy"          | []
    "x"  | "yyyyyyyyx"          | [8]
    "x"  | "yyyyyyyyyx"         | [9]
  }
}
