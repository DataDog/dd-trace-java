package org.example

import spock.lang.Specification

class TestSucceedSpock extends Specification {

  def "test success"() {
    expect:
    1 == 1
  }
}
