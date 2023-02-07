import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.kotlin.coroutines.AbstractKotlinCoroutineInstrumentationTest
import kotlinx.coroutines.CoroutineDispatcher

class KotlinCoroutineInstrumentationTest extends AbstractKotlinCoroutineInstrumentationTest<KotlinCoroutineTests> {

  @Override
  protected KotlinCoroutineTests getCoreKotlinCoroutineTestsInstance(CoroutineDispatcher dispatcher) {
    return new KotlinCoroutineTests(dispatcher)
  }

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
    dispatcher << AbstractKotlinCoroutineInstrumentationTest.dispatchersToTest
  }
}
