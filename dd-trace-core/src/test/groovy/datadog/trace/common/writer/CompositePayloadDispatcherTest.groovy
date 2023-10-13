package datadog.trace.common.writer

import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddintake.DDIntakeApi
import datadog.trace.core.CoreSpan
import spock.lang.Specification

class CompositePayloadDispatcherTest extends Specification {

  def "test onDroppedTrace"() {
    given:
    def dispatcherA = Mock(PayloadDispatcher)
    def dispatcherB = Mock(PayloadDispatcher)
    def dispatcher = new CompositePayloadDispatcher(dispatcherA, dispatcherB)

    def droppedSpansCount = 1234

    when:
    dispatcher.onDroppedTrace(droppedSpansCount)

    then:
    1 * dispatcherA.onDroppedTrace(droppedSpansCount)
    1 * dispatcherB.onDroppedTrace(droppedSpansCount)
    0 * _
  }

  def "test addTrace"() {
    given:
    def dispatcherA = Mock(PayloadDispatcher)
    def dispatcherB = Mock(PayloadDispatcher)
    def dispatcher = new CompositePayloadDispatcher(dispatcherA, dispatcherB)

    def trace = Collections.singletonList(Mock(CoreSpan))

    when:
    dispatcher.addTrace(trace)

    then:
    1 * dispatcherA.addTrace(trace)
    1 * dispatcherB.addTrace(trace)
    0 * _
  }

  def "test flush"() {
    given:
    def dispatcherA = Mock(PayloadDispatcher)
    def dispatcherB = Mock(PayloadDispatcher)
    def dispatcher = new CompositePayloadDispatcher(dispatcherA, dispatcherB)

    when:
    dispatcher.flush()

    then:
    1 * dispatcherA.flush()
    1 * dispatcherB.flush()
    0 * _
  }

  def "test getApis"() {
    given:
    def dispatcherA = Mock(PayloadDispatcher)
    def dispatcherB = Mock(PayloadDispatcher)
    def dispatcher = new CompositePayloadDispatcher(dispatcherA, dispatcherB)

    dispatcherA.getApis() >> [DDIntakeApi]
    dispatcherB.getApis() >> [DDAgentApi]

    when:
    def apis = dispatcher.getApis()

    then:
    apis == [DDIntakeApi, DDAgentApi]
  }
}
