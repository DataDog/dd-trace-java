package mule4

import datadog.trace.test.util.DDSpecification

class MuleMemoryForkedTest extends DDSpecification {

  def "Forked memory should be high"() {
    when:
    def max = Runtime.getRuntime().maxMemory()

    then:
    max == 768 * 1024 * 1024
  }
}
