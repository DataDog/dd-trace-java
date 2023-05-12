package org.example

import spock.lang.Specification

class TestParameterizedSpock extends Specification {

  def "test add #a and #b"() {
    expect:
    a + b == c

    where:
    a | b | c
    1 | 2 | 3
    4 | 4 | 8
  }
}
