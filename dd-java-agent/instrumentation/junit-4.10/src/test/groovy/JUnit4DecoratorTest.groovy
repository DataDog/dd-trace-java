import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DisableTestTrace
import datadog.trace.instrumentation.junit4.JUnit4Decorator
import org.example.TestDisableTestTrace
import org.example.TestSucceed
import org.junit.runner.Description
import spock.lang.Shared

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4DecoratorTest extends AgentTestRunner {

  @Shared
  def decorator = new JUnit4Decorator()

  def "skip trace false in test class without annotation"() {
    setup:
    def description = Description.createTestDescription(TestSucceed, "test_success")

    expect:
    !decorator.skipTrace(description)
  }

  def "skip trace false in test suite without test class"() {
    setup:
    def description = Description.createSuiteDescription("test_success")

    expect:
    !decorator.skipTrace(description)
  }

  def "skip trace true in test class with annotation"() {
    setup:
    def description = Description.createTestDescription(TestDisableTestTrace, "test_success")

    expect:
    decorator.skipTrace(description)
  }

}
