package org.example

import datadog.trace.api.civisibility.CIConstants
import spock.lang.Specification
import spock.lang.Tag

class TestSucceedSpockUnskippable extends Specification {

  @Tag(CIConstants.Tags.ITR_UNSKIPPABLE_TAG)
  def "test success"() {
    expect:
    1 == 1
  }
}
