import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
// import spock.lang.Unroll

class AkkaActorTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.java_concurrent.enabled", "true")
  }

  def setupSpec() {
  }

  @Override
  void afterTest() {
    // Ignore failures to instrument sun proxy classes
  }

  def "some test"() {
    setup:
    AkkaActors akkaTester = new AkkaActors()
    akkaTester.basicGreeting()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
  }
}
