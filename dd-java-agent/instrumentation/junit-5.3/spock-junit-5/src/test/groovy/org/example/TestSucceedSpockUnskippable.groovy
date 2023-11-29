package org.example

import datadog.trace.api.civisibility.InstrumentationBridge
import spock.lang.Specification
import spock.lang.Tag

class TestSucceedSpockUnskippable extends Specification {

  @Tag(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)
  def "test success"() {
    expect:
    1 == 1
  }
}
