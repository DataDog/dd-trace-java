import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import datadog.trace.instrumentation.kotlin.coroutines.CoreKotlinCoroutineTests
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.toChannel

@SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
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
  override fun tracePreventedByCancellation(): Int {
    return super.tracePreventedByCancellation()
  }

  @Trace
  override fun tracedAcrossThreadsWithNested(): Int {
    return super.tracedAcrossThreadsWithNested()
  }

  @Trace
  override fun traceWithDeferred(): Int {
    return super.traceWithDeferred()
  }

  @Trace
  override fun tracedWithDeferredFirstCompletions(): Int {
    return super.tracedWithDeferredFirstCompletions()
  }

  @Trace
  override fun tracedWithSuspendingCoroutines(): Int {
    return super.tracedWithSuspendingCoroutines()
  }

  @Trace
  override fun tracedWithLazyStarting(): Int {
    return super.tracedWithLazyStarting()
  }

  @Trace
  override fun tracedChild(opName: String) {
    super.tracedChild(opName)
  }
}
