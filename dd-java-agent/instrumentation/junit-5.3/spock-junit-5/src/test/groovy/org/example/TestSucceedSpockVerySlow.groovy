package org.example

import spock.lang.Specification

class TestSucceedSpockVerySlow extends Specification {

  def "test success"() {
    expect:
    Thread.sleep(2100)
    1 == 1
  }
}
