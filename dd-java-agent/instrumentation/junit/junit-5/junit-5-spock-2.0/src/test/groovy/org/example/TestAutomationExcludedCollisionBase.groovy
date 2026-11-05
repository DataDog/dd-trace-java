package org.example

import spock.lang.Specification

class TestAutomationExcludedCollisionBase extends Specification {
  static int normalExecutions

  def "same_named_feature"() {
    expect:
    ++normalExecutions < 0
  }
}
