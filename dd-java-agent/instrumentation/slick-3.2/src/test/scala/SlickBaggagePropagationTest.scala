import datadog.context.Context
import datadog.trace.agent.test.AbstractInstrumentationTest
import datadog.trace.bootstrap.instrumentation.api.Baggage
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotNull, assertTrue}
import org.junit.jupiter.api.Test

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** Regression test for https://github.com/DataDog/dd-trace-java/issues/11758.
  *
  * SlickRunnableInstrumentation's Construct advice calls AdviceUtils.capture(), which reads only
  * activeSpan() and bails out entirely when there is no active span - it never looks at the rest of
  * the Context. So baggage attached without an active Datadog span (e.g. OTel baggage set
  * independently of a local span) is silently dropped when the Runnable resumes on a Slick
  * AsyncExecutor thread, instead of being carried across like the rest of the Context.
  */
class SlickBaggagePropagationTest extends AbstractInstrumentationTest {

  @Test
  def baggagePropagatesAcrossAsyncExecutorHandoff(): Unit = {
    val database = new SlickUtils(AbstractInstrumentationTest.writer)

    val baggage         = Baggage.create(Collections.singletonMap("user.id", "abc123"))
    val capturedBaggage = new AtomicReference[Baggage]()
    val taskRan         = new CountDownLatch(1)

    // Baggage attached with no active span - AdviceUtils.capture() bails out as soon as
    // activeSpan() is null, so it never even looks at the rest of the Context.
    val baggageScope = Context.current().`with`(baggage).attach()
    try {
      database.runOnAsyncExecutor(new Runnable {
        override def run(): Unit = {
          capturedBaggage.set(Baggage.fromContext(Context.current()))
          taskRan.countDown()
        }
      })
    } finally {
      baggageScope.close()
    }

    assertTrue(taskRan.await(10, TimeUnit.SECONDS), "task did not run on the AsyncExecutor")

    val propagated = capturedBaggage.get()
    assertNotNull(
      propagated,
      "baggage must survive the hand-off onto the Slick AsyncExecutor thread; null means" +
        " SlickRunnableInstrumentation only captured the span, dropping the rest of the Context"
    )
    assertEquals("abc123", propagated.asMap().get("user.id"))
  }
}
