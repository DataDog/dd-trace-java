package com.datadog.iast.taint

import spock.lang.Specification
import static com.datadog.iast.taint.TaintUtils.*

class TaintUtilsTest extends Specification {

  void 'taintFormat with empty ranges'() {
    expect:
    taintFormat(s, toRanges(ranges)) == result

    where:
    s     | ranges | result
    "123" | []     | "123"
  }

  void 'taintFormat amd fromTaintFormat'() {
    expect:
    taintFormat(s, toRanges(ranges)) == result

    and:
    fromTaintFormat(result) == toRanges(ranges)

    where:
    s     | ranges                   | result
    null  | null                     | null
    ""    | null                     | ""
    "123" | null                     | "123"
    "123" | [[0, 1]]                 | "==>1<==23"
    "123" | [[0, 2]]                 | "==>12<==3"
    "123" | [[0, 3]]                 | "==>123<=="
    "123" | [[1, 1]]                 | "1==>2<==3"
    "123" | [[1, 2]]                 | "1==>23<=="
    "123" | [[0, 1], [1, 1], [2, 1]] | "==>1<====>2<====>3<=="
  }
}
