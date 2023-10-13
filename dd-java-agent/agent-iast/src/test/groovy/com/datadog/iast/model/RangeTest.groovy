package com.datadog.iast.model

import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.test.util.DDSpecification

class RangeTest extends DDSpecification {

  def 'shift'() {
    given:
    final source = new Source(SourceTypes.NONE, null, null)
    final orig = new Range(start, length, source, VulnerabilityMarks.SQL_INJECTION_MARK)

    when:
    final result = orig.shift(shift)

    then:
    result != null
    result.source == source
    result.start == startResult
    result.length == lengthResult
    result.marks == VulnerabilityMarks.SQL_INJECTION_MARK
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
    final source = new Source(SourceTypes.NONE, null, null)
    final orig = new Range(0, 1, source, Range.NOT_MARKED)

    when:
    final result = orig.shift(0)

    then:
    result.is(orig)
  }
}
