import com.datadog.profiling.exceptions.ExceptionProfiling
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import spock.lang.Requires

@Requires({ jvm.java11Compatible })
class ExceptionInstrumentationTest extends AgentTestRunner {
  def testInstrumentation() {
    setup:
    def rec = JfrHelper.startRecording()
    def exceptionCount = 1000
    def delay = 2
    def config = Config.get()
    ExceptionProfiling.init(config)

    when:
    for (int i = 0; i < exceptionCount; i++) {
      try {
        Thread.sleep(delay)
        throw new TestException("msg")
      } catch (Throwable ignored) {
      }
    }

    def events = JfrHelper.stopRecording(rec)
    def projectedEventsSize = events.size() * (config.getProfilingExceptionSamplerTimeWindow() * 1000 / (delay * exceptionCount))
    def scaled = (exceptionCount / (double) events.size()) / config.getProfilingExceptionSamplerSlidingWindow()
    then:
    scaled >= 1
    projectedEventsSize <= config.getProfilingExceptionSamplerTimeWindow() * 2
    events.every { !it.getString("type").isEmpty() }
  }
}
