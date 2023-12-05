package org.example

import datadog.trace.api.civisibility.InstrumentationBridge
import spock.lang.Specification
import spock.lang.Tag

@Tag(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)
class TestSucceedSpockUnskippableSuite extends Specification {

  def "test success"() {
    expect:
    1 == 1
  }
}
