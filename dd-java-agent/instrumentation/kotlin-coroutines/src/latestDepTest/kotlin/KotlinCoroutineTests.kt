import datadog.trace.api.Trace
import datadog.trace.instrumentation.kotlin.coroutines.CoreKotlinCoroutineTests
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
class KotlinCoroutineTests(dispatcher: CoroutineDispatcher) : CoreKotlinCoroutineTests(dispatcher) {

  @Trace
  fun tracedAcrossFlows(withModifiedContext: Boolean): Int = runTest {
    val producer = flow {
      repeat(3) {
        tracedChild("produce_$it")
        if (withModifiedContext) {
          withTimeout(100) {
            emit(it)
          }
        } else {
          emit(it)
        }
      }
    }.flowOn(jobName("producer"))

    launch(jobName("consumer")) {
      producer.collect {
        tracedChild("consume_$it")
      }
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
  override fun traceAfterTimeout(): Int {
    return super.traceAfterTimeout()
  }

  @Trace
  override fun traceAfterDelay(): Int {
    return super.traceAfterDelay()
  }

  @Trace
  override fun tracedChild(opName: String) {
    super.tracedChild(opName)
  }
}
