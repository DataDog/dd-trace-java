package org.example

import spock.lang.Specification

class TestSucceedAndFailedSpock extends Specification {

  def "test success"() {
    expect:
    1 == 1
  }

  def "test failure"() {
    expect:
    1 == 2
  }

  def "test another success"() {
    expect:
    2 == 2
  }
}
