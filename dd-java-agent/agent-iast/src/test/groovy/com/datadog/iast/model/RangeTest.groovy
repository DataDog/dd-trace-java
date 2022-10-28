package com.datadog.iast.model

import datadog.trace.test.util.DDSpecification

class RangeTest extends DDSpecification {

  def 'shift'() {
    given:
    final source = new Source(SourceType.NONE, null, null)
    final orig = new Range(start, length, source)

    when:
    final result = orig.shift(shift)

    then:
    result != null
    result.source == source
    result.start == startResult
    result.length == lengthResult
    result.isValid() == valid

    where:
    start | length | shift | startResult | lengthResult | valid
    0     | 1      | 0     | 0           | 1            | true
    0     | 1      | 1     | 1           | 1            | true
    0     | 1      | -1    | -1          | 1            | false
    1     | 1      | 0     | 1           | 1            | true
    1     | 1      | 1     | 2           | 1            | true
    1     | 1      | -1    | 0           | 1            | true
  }

  def 'shift zero'() {
    given:
    final source = new Source(SourceType.NONE, null, null)
    final orig = new Range(0, 1, source)

    when:
    final result = orig.shift(0)

    then:
    result.is(orig)
  }
}
