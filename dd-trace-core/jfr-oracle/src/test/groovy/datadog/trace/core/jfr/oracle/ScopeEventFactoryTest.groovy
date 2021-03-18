package datadog.trace.core.jfr.oracle

import datadog.trace.core.DDSpanContext
import datadog.trace.core.jfr.DDNoopScopeEvent
import spock.lang.Requires
import spock.lang.Specification

import java.nio.file.Files

@Requires({
  jvm.java8Compatible
})
class ScopeEventFactoryTest extends Specification {

  def factory = new ScopeEventFactory()

  def "Returns noop event if profiling is not running"() {
    when:
    def event = factory.create(null)

    then:
    event == DDNoopScopeEvent.INSTANCE
  }

  def "Returns real event if profiling is running"() {
    setup:
    def recording = JfrTestUtils.startRecording()

    when:
    def event = factory.create(Mock(DDSpanContext))
    recording.stop()

    then:
    event instanceof ScopeEvent
  }
}
