import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.kotlin.coroutines.AbstractKotlinCoroutineInstrumentationTest
import kotlinx.coroutines.CoroutineDispatcher

class KotlinCoroutineInstrumentationTest extends AbstractKotlinCoroutineInstrumentationTest<KotlinCoroutineTests> {

  @Override
  protected KotlinCoroutineTests getCoreKotlinCoroutineTestsInstance(CoroutineDispatcher dispatcher) {
    return new KotlinCoroutineTests(dispatcher)
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
    findSpan(trace, "produce_2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "consume_2").context().getParentId() == trace[0].context().getSpanId()

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
    findSpan(trace, "produce_2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "consume_2").context().getParentId() == trace[0].context().getSpanId()

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
