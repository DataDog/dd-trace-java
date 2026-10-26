import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.kotlin.coroutines.AbstractKotlinCoroutineInstrumentationTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class KotlinCoroutineInstrumentationTest extends AbstractKotlinCoroutineInstrumentationTest<KotlinCoroutineTests> {

  @Override
  protected KotlinCoroutineTests getCoreKotlinCoroutineTestsInstance(CoroutineDispatcher dispatcher) {
    return new KotlinCoroutineTests(dispatcher)
  }

  def "span activated inside withContext survives suspension mdc=#mdc differentDispatcher=#differentDispatcher"() {
    setup:
    int expectedNumberOfSpans = getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).scopedSpanSurvivesSuspension(mdc, differentDispatcher)
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "scoped-span").spanContext().parentId == findSpan(trace, "seed").spanContext().spanId
    findSpan(trace, "child").spanContext().parentId == findSpan(trace, "scoped-span").spanContext().spanId
    findSpan(trace, "after-child").parentId == findSpan(trace, "after-context").spanId

    where:
    mdc   | differentDispatcher
    false | false
    true  | false
    false | true
    true  | true
  }

  def "withContext keeps a root started inside its caller open after early finish"() {
    when:
    getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).withContextKeepsEarlyFinishedRootOpen()

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 2
    findSpan(trace, "late-child").parentId == findSpan(trace, "early-root").spanId
  }

  def "withContext isolates an equal but distinct context across suspension"() {
    when:
    getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).equalWithContextSpanSurvivesSuspension()

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 3
    findSpan(trace, "child").parentId == findSpan(trace, "scoped").spanId
    findSpan(trace, "scoped").parentId == findSpan(trace, "root").spanId
  }

  def "flow emission from an equivalent context kind=#kind suspendFlow=#suspendFlow"() {
    when:
    getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).flowEmissionInContext(kind, suspendFlow)

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 4
    findSpan(trace, "emission").parentId == findSpan(trace, "flow-root").spanId
    findSpan(trace, "collect-child").parentId == findSpan(trace, "emission").spanId
    findSpan(trace, "after-flow").parentId == findSpan(trace, "flow-root").spanId

    where:
    [kind, suspendFlow] << [["full", "without-job", "dispatcher", "name"], [false, true]].combinations()
  }

  def "flow still enforces exception transparency across context checks"() {
    when:
    getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).flowWithFailedEmission()

    then:
    def error = thrown(IllegalStateException)
    error.message.contains("exception transparency is violated")
  }

  def "withContext preserves Kotlin copyable element merge semantics"() {
    when:
    new KotlinCoroutineTests(Dispatchers.Default).withContextPreservesCopyableElements()

    then:
    noExceptionThrown()
  }

  def "kotlin traced across flows #dispatcherName"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedAcrossFlows(false)
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.tracedAcrossFlows"
    findSpan(trace, "produce_2").spanContext().parentId == trace[0].spanContext().spanId
    findSpan(trace, "consume_2").spanContext().parentId == trace[0].spanContext().spanId

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "kotlin traced across flows with modified context #dispatcherName"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedAcrossFlows(true)
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.tracedAcrossFlows"
    findSpan(trace, "produce_2").spanContext().parentId == trace[0].spanContext().spanId
    findSpan(trace, "consume_2").spanContext().parentId == trace[0].spanContext().spanId

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "kotlin trace consistent after flow"() {
    setup:
    KotlinCoroutineTests kotlinTest = new KotlinCoroutineTests(dispatcher)
    int expectedNumberOfSpans = kotlinTest.traceAfterFlow()
    TEST_WRITER.waitForTraces(1)

    expect:
    assertTraces(1) {
      trace(expectedNumberOfSpans, true) {
        span(2) {
          operationName "trace.annotation"
          parent()
        }
        span(1) {
          operationName "outside-flow"
          childOf span(2)
        }
        span(0) {
          operationName "inside-flow"
          childOf span(2)
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }
}
