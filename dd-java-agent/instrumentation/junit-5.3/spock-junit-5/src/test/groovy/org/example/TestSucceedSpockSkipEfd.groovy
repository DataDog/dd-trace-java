package org.example

import datadog.trace.api.civisibility.CIConstants
import spock.lang.Specification
import spock.lang.Tag

class TestSucceedSpockSkipEfd extends Specification {

  @Tag(CIConstants.Tags.EFD_DISABLE_TAG)
  def "test success"() {
    expect:
    1 == 1
  }
}
