import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.kotlin.coroutines.AbstractKotlinCoroutineInstrumentationTest
import kotlin.OptIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(markerClass = ExperimentalCoroutinesApi)
class KotlinCoroutineInstrumentationTest extends AbstractKotlinCoroutineInstrumentationTest<KotlinCoroutineTests> {

  @Override
  protected KotlinCoroutineTests getCoreKotlinCoroutineTestsInstance(CoroutineDispatcher dispatcher) {
    return new KotlinCoroutineTests(dispatcher)
  }

  def "kotlin traced across channels #dispatcherName"() {
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
    [dispatcherName, dispatcher] << dispatchersToTest
  }
}
