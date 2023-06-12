package datadog.trace.civisibility.events

import datadog.trace.api.civisibility.events.TestEventsHandler
import spock.lang.Specification

import java.nio.file.Paths

class CachingTestEventsHandlerFactoryTest extends Specification {

  def "verify TestEventsHandler is cached"() {
    given:
    def delegate = Mock(TestEventsHandler.Factory)
    delegate.create("component", "framework", "version", Paths.get("path")) >> { Mock(TestEventsHandler) }

    when:
    def factory = new CachingTestEventsHandlerFactory(delegate, 1)
    def handlerA = factory.create("component", "framework", "version", Paths.get("path"))
    def handlerB = factory.create("component", "framework", "version", Paths.get("path"))

    then:
    handlerA == handlerB
    1 * delegate.create("component", "framework", "version", Paths.get("path"))
    0 * _
  }
}
