import datadog.trace.api.Trace
import datadog.trace.instrumentation.kotlin.coroutines.CoreKotlinCoroutineTests
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.toChannel

@OptIn(ExperimentalCoroutinesApi::class)
class KotlinCoroutineTests(dispatcher: CoroutineDispatcher) : CoreKotlinCoroutineTests(dispatcher) {

  @Trace
  fun tracedAcrossChannels(): Int = runTest {
    val producer = produce(jobName("producer")) {
      repeat(3) {
        tracedChild("produce_$it")
        send(it)
      }
    }

    val actor = actor<Int>(jobName("consumer")) {
      consumeEach {
        tracedChild("consume_$it")
      }
    }

    @Suppress("DEPRECATION_ERROR")
    producer.toChannel(actor)
    actor.close()

    7
  }

  @Trace
  override fun tracePreventedByCancellation(): Int = super.tracePreventedByCancellation()

  @Trace
  override fun tracedAcrossThreadsWithNested(): Int = super.tracedAcrossThreadsWithNested()

  @Trace
  override fun traceWithDeferred(): Int = super.traceWithDeferred()

  @Trace
  override fun tracedWithDeferredFirstCompletions(): Int = super.tracedWithDeferredFirstCompletions()

  @Trace
  override fun tracedWithSuspendingCoroutines(): Int = super.tracedWithSuspendingCoroutines()

  @Trace
  override fun tracedWithLazyStarting(): Int = super.tracedWithLazyStarting()

  @Trace
  override fun traceAfterTimeout(): Int = super.traceAfterTimeout()

  @Trace
  override fun traceAfterDelay(): Int = super.traceAfterDelay()

  @Trace
  override fun tracedChild(opName: String) {
    super.tracedChild(opName)
  }
}
