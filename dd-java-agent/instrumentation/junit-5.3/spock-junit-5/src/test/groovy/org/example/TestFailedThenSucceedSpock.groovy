package org.example

import spock.lang.Specification

class TestFailedThenSucceedSpock extends Specification {

  public static int testExecutionCount = 0

  def "test failed then succeed"() {
    expect:
    ++testExecutionCount > 3
  }
}
