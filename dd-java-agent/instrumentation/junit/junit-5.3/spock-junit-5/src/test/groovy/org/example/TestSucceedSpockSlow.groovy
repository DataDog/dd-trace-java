package org.example

import spock.lang.Specification

class TestSucceedSpockSlow extends Specification {

  def "test success"() {
    expect:
    Thread.sleep(1100)
    1 == 1
  }
}
