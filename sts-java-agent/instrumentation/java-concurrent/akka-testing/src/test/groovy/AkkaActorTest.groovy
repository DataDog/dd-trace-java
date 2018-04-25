import stackstate.opentracing.STSSpan
import spock.lang.Unroll
import stackstate.trace.agent.test.AgentTestRunner

class AkkaActorTest extends AgentTestRunner {
  static {
    System.setProperty("sts.integration.java_concurrent.enabled", "true")
  }

  @Override
  void afterTest() {
    // Ignore failures to instrument sun proxy classes
  }

  @Unroll
  def "akka #testMethod" () {
    setup:
    AkkaActors akkaTester = new AkkaActors()
    akkaTester."$testMethod"()

    TEST_WRITER.waitForTraces(1)
    List<STSSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace[0].getOperationName() == "AkkaActors.$testMethod"
    findSpan(trace, "$expectedGreeting, Akka").context().getParentId() == trace[0].getSpanId()

    where:
    testMethod         | expectedGreeting
    "basicTell"        | "Howdy"
    "basicAsk"         | "Howdy"
    "basicForward"     | "Hello"
  }

  private STSSpan findSpan(List<STSSpan> trace, String opName) {
    for (STSSpan span : trace) {
      if (span.getOperationName() == opName) {
        return span
      }
    }
    return null
  }
}
