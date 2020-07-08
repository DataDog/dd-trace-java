import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DisableTestTrace
import org.junit.runner.JUnitCore
import spock.lang.Shared

class JUnit4Test extends AgentTestRunner {

  @Shared
  def runner = new JUnitCore()

  @DisableTestTrace(reason = "avoid self-tracing")
  def "test success generate spans"() {
    setup:
    runner.run(TestSucceed)

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          spanType "junit"
        }
      }
    }
  }
}
