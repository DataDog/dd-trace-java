import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan

class ScalaInstrumentationTest extends InstrumentationSpecification {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  def "scala futures and callbacks"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.traceWithFutureAndCallbacks()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "ScalaConcurrentTests.traceWithFutureAndCallbacks"
    findSpan(trace, "goodFuture").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "badFuture").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "good complete").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "bad complete").spanContext().getParentId() == trace[0].spanContext().getSpanId()
  }

  def "scala propagates across futures with no traces"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.tracedAcrossThreadsWithNoTrace()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "ScalaConcurrentTests.tracedAcrossThreadsWithNoTrace"
    findSpan(trace, "callback").spanContext().getParentId() == trace[0].spanContext().getSpanId()
  }

  def "scala either promise completion"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.traceWithPromises()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "ScalaConcurrentTests.traceWithPromises"
    findSpan(trace, "keptPromise").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "keptPromise2").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "brokenPromise").spanContext().getParentId() == trace[0].spanContext().getSpanId()
  }

  def "scala first completed future"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.tracedWithFutureFirstCompletions()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "timeout1").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "timeout2").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "timeout3").spanContext().getParentId() == trace[0].spanContext().getSpanId()
  }

  private DDSpan findSpan(List<DDSpan> trace, String opName) {
    for (DDSpan span : trace) {
      if (span.getOperationName() == opName) {
        return span
      }
    }
    return null
  }
}
