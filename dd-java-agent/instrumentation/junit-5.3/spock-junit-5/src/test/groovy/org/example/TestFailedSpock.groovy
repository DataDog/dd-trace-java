package org.example

import spock.lang.Specification

class TestFailedSpock extends Specification {

  def "test failed"() {
    expect:
    1 == 2
  }
}
