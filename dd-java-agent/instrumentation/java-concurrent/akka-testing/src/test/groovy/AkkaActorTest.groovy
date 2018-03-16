import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
// import spock.lang.Unroll

class AkkaActorTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.java_concurrent.enabled", "true")
  }

  @Override
  void afterTest() {
    // Ignore failures to instrument sun proxy classes
  }

  def "tell message"() {
    setup:
    AkkaActors akkaTester = new AkkaActors()
    akkaTester.basicTell()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace[0].getOperationName() == "AkkaActors.basicTell"
    findSpan(trace, "Howdy, Akka").context().getParentId() == trace[0].getSpanId()
  }

  def "ask message"() {
    setup:
    AkkaActors akkaTester = new AkkaActors()
    akkaTester.basicAsk()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace[0].getOperationName() == "AkkaActors.basicAsk"
    findSpan(trace, "Howdy, Akka").context().getParentId() == trace[0].getSpanId()
  }

  def "forward message"() {
    setup:
    AkkaActors akkaTester = new AkkaActors()
    akkaTester.basicForward()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace[0].getOperationName() == "AkkaActors.basicForward"
    findSpan(trace, "Hello, Akka").context().getParentId() == trace[0].getSpanId()
  }
  // forward

  // TODO: Test akka throughput
  // An actor may process more than one message before releasing the thread.
  // Not sure how to test this yet...

  private DDSpan findSpan(List<DDSpan> trace, String opName) {
    for (DDSpan span : trace) {
      if (span.getOperationName() == opName) {
        return span
      }
    }
    return null
  }
}
