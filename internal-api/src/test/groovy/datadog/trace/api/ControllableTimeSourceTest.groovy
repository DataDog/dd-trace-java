package datadog.trace.api

import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.test.util.DDSpecification

class ControllableTimeSourceTest extends DDSpecification {
  def "test time source changes"() {
    given:
    def timeSource = new ControllableTimeSource()

    when:
    timeSource.set(1000)

    then:
    timeSource.getNanoTime() == 1000

    when:
    timeSource.advance(500)

    then:
    timeSource.getNanoTime() == 1500

    when:
    timeSource.advance(-800)

    then:
    timeSource.getNanoTime() == 700
  }
}
