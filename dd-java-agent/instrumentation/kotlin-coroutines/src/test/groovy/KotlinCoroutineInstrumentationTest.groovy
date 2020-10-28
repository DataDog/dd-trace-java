import datadog.trace.core.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadPoolDispatcherKt

class KotlinCoroutineInstrumentationTest extends AgentTestRunner {

  static dispatchersToTest = [
    Dispatchers.Default,
    Dispatchers.IO,
    //Dispatchers.Unconfined,
    ThreadPoolDispatcherKt.newSingleThreadContext("Single-Thread"),
    ThreadPoolDispatcherKt.newFixedThreadPoolContext(2, "2-Fixed-Thread-Pool"),
    ThreadPoolDispatcherKt.newFixedThreadPoolContext(8, "8-Fixed-Thread-Pool"),
  ]

  def "kotlin traced across channels"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedAcrossChannels()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.tracedAcrossChannels"
    findSpan(trace, "produce_2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "consume_2").context().getParentId() == trace[0].context().getSpanId()

    where:
    dispatcher << dispatchersToTest
  }

  def "kotlin cancellation prevents trace"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracePreventedByCancellation()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.tracePreventedByCancellation"
    findSpan(trace, "preLaunch").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "postLaunch") == null

    where:
    dispatcher << dispatchersToTest
  }

  def "kotlin propagates across nested jobs"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedAcrossThreadsWithNested()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.tracedAcrossThreadsWithNested"
    findSpan(trace, "nested").context().getParentId() == trace[0].context().getSpanId()

    where:
    dispatcher << dispatchersToTest
  }

  def "kotlin either deferred completion"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.traceWithDeferred()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.traceWithDeferred"
    findSpan(trace, "keptPromise").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "keptPromise2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "brokenPromise").context().getParentId() == trace[0].context().getSpanId()

    where:
    dispatcher << dispatchersToTest
  }

  def "kotlin first completed deferred"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedWithDeferredFirstCompletions()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "timeout1").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "timeout2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "timeout3").context().getParentId() == trace[0].context().getSpanId()

    where:
    dispatcher << dispatchersToTest
  }

  private static DDSpan findSpan(List<DDSpan> trace, String opName) {
    for (DDSpan span : trace) {
      if (span.getOperationName() == opName) {
        return span
      }
    }
    return null
  }

//  def "test concurrent suspend functions"() {
//    setup:
//    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(Dispatchers.Default)
//    int numIters = 100
//    HashSet<Long> seenItersA = new HashSet<>()
//    HashSet<Long> seenItersB = new HashSet<>()
//    HashSet<Long> expectedIters = new HashSet<>((0L..(numIters-1)).toList())
//
//    when:
//    kotlinTest.launchConcurrentSuspendFunctions(numIters)
//
//    then:
//    // This generates numIters each of "a calls a2" and "b calls b2" traces.  Each
//    // trace should have a single pair of spans (a and a2) and each of those spans
//    // should have the same iteration number (attribute "iter").
//    // The traces are in some random order, so let's keep track and make sure we see
//    // each iteration # exactly once
//    assertTraces(numIters*2) {
//      for(int i=0; i < numIters*2; i++) {
//        trace(i, 2) {
//          boolean a = false
//          long iter = -1
//          span(0) {
//            a = span.name.matches("a")
//            iter = span.getAttributes().get("iter").getLongValue()
//            (a ? seenItersA : seenItersB).add(iter)
//            operationName(a ? "a" : "b")
//          }
//          span(1) {
//            operationName(a ? "a2" : "b2")
//            childOf(span(0))
//            assert span.getAttributes().get("iter").getLongValue() == iter
//
//          }
//        }
//      }
//    }
//    assert seenItersA.equals(expectedIters)
//    assert seenItersB.equals(expectedIters)
//  }

}
