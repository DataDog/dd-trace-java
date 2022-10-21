import ch.qos.logback.classic.PatternLayout
import datadog.trace.agent.test.AgentTestRunner

class PatternLayoutInstrumentationTest extends AgentTestRunner {
  def "test pattern modifications are correct"() {
    given:
    PatternLayout patternLayout = new PatternLayout()

    when:
    patternLayout.setPattern(pattern)

    then:
    patternLayout.getPattern() == expectation

    where:
    pattern | expectation
    "%-5level [%thread]: %message%n" | "%-5level [%thread]:- %X{dd.trace_id} %X{dd.span_id} - %message%n"
    "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n" | "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -- %X{dd.trace_id} %X{dd.span_id} - %msg%n"
  }
}
