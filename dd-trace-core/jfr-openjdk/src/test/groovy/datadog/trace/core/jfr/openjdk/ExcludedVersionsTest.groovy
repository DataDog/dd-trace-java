package datadog.trace.core.jfr.openjdk

import spock.lang.Specification

class ExcludedVersionsTest extends Specification {
  def "expect #version is #excludedDescription"() {

    expect:
    excluded == ExcludedVersions.isVersionExcluded(version)

    where:
    version     | excluded
    '1.7'       | false
    '1.7.0'     | false
    '1.7.0_1'   | false
    '1.7.0_261' | false
    '1.7.0_262' | false
    '1.7.0_265' | false
    '1.8'       | false
    '1.8.0'     | false
    '1.8.0_1'   | false
    '1.8.0_261' | false
    '1.8.0_262' | true
    '1.8.0_265' | true
    '1.8.1'     | false
    '1.8.1_1'   | false
    '1.8.1_261' | false
    '1.8.1_262' | false
    '1.8.1_265' | false
    '9-ea'      | true
    '9'         | true
    '9.0'       | true
    '10-ea'     | true
    '10'        | true
    '10.0'      | true
    '11'        | false
    '12'        | false
    '13'        | false
    '14'        | false
    '15'        | false
    '90'        | false
    '100'       | false

    excludedDescription = excluded ? 'excluded' : 'not excluded'
  }
}
