import datadog.trace.api.Trace
import datadog.trace.instrumentation.kotlin.coroutines.CoreKotlinCoroutineTests
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Workaround for Groovy 4 breaking Kotlin SAM conversion for FlowCollector
private suspend inline fun <T> Flow<T>.forEach(crossinline action: suspend (T) -> Unit) = collect(object : FlowCollector<T> {
  override suspend fun emit(value: T) = action(value)
})

class KotlinCoroutineTests(dispatcher: CoroutineDispatcher) : CoreKotlinCoroutineTests(dispatcher) {

  @Trace
  fun tracedAcrossFlows(withModifiedContext: Boolean): Int = runTest {
    // Use channelFlow when emitting from modified context (withTimeout) as regular flow doesn't allow it
    val producer: Flow<Int> = if (withModifiedContext) {
      channelFlow {
        repeat(3) {
          tracedChild("produce_$it")
          withTimeout(100) { send(it) }
        }
      }
    } else {
      flow {
        repeat(3) {
          tracedChild("produce_$it")
          emit(it)
        }
      }
    }.flowOn(jobName("producer"))

    launch(jobName("consumer")) {
      producer.forEach { tracedChild("consume_$it") }
    }

    7
  }

  @Trace
  fun traceAfterFlow(): Int = runTest {
    val f = flow {
      childSpan("inside-flow").activateAndUse {
        println("insideFlowSpan")
      }
      emit(1)
    }.flowOn(Dispatchers.IO)
    val ff = f.single()

    childSpan("outside-flow").activateAndUse {
      println("hello $ff")
    }

    3
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
