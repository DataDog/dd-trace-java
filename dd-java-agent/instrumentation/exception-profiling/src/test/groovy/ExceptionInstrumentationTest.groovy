
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionEvents
import spock.lang.Requires

@Requires({ jvm.java11Compatible })
class ExceptionInstrumentationTest extends AgentTestRunner {
  def testInstrumentation() {
    setup:
      def rec = JfrHelper.startRecording()
      def exceptionCount = 1000
      def delay = 2

    when:
      for (int i = 0; i < exceptionCount; i++) {
        try {
          Thread.sleep(delay)
          throw new TestException("msg")
        } catch (Throwable ignored) {
        }
      }

      def events = JfrHelper.stopRecording(rec)
      def projectedEventsSize = events.size() * (ExceptionEvents.timeWindowMs / (delay * exceptionCount))
      def scaled = (exceptionCount / (double)events.size()) / ExceptionEvents.interval
    then:
      scaled >= 1
      projectedEventsSize <= ExceptionEvents.maxWindowSamples * 2
      events.every {!it.getString("type").isEmpty()}
  }
}
