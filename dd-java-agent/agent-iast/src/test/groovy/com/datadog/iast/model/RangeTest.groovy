package com.datadog.iast.model

import static com.datadog.iast.model.VulnerabilityType.SQL_INJECTION
import static com.datadog.iast.model.VulnerabilityType.XSS
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED
import static datadog.trace.api.iast.VulnerabilityMarks.SQL_INJECTION_MARK
import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class RangeTest extends DDSpecification {

  @Shared
  int  multipleMarks  = SQL_INJECTION_MARK | XSS_MARK

  def 'shift'() {
    given:
    final source = new Source(SourceTypes.NONE, null, null)
    final orig = new Range(start, length, source, SQL_INJECTION_MARK)

    when:
    final result = orig.shift(shift)

    then:
    result != null
    result.source == source
    result.start == startResult
    result.length == lengthResult
    result.marks == SQL_INJECTION_MARK
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
    final orig = new Range(0, 1, source, VulnerabilityMarks.NOT_MARKED)

    when:
    final result = orig.shift(0)

    then:
    result.is(orig)
  }



  void 'test getMarkedVulnerabilities'() {
    given:
    final source = new Source(SourceTypes.NONE, null, null)
    final range = new Range(0, 1, source, marks)

    when:
    final vulnerabilities = range.getMarkedVulnerabilities()

    then:
    vulnerabilities == expected

    where:
    marks                           | expected
    NOT_MARKED                      | null
    SQL_INJECTION_MARK              | [SQL_INJECTION] as Set
    multipleMarks | [SQL_INJECTION, XSS] as Set
  }
}
