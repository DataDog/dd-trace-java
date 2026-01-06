package org.example

import spock.lang.Specification

class TestFailedThenSucceedParameterizedSpock extends Specification {

  public static int testExecutionCount = 0

  def "test add #a and #b"() {
    expect:
    ++testExecutionCount > 2

    where:
    a | b | c
    1 | 2 | 3
    4 | 4 | 8
  }
}
