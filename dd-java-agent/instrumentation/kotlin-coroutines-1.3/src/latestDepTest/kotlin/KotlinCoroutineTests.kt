import datadog.trace.api.Trace
import datadog.trace.instrumentation.kotlin.coroutines.CoreKotlinCoroutineTests
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class KotlinCoroutineTests(dispatcher: CoroutineDispatcher) : CoreKotlinCoroutineTests(dispatcher) {

  @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
  fun withContextPreservesCopyableElements() {
    val merges = AtomicInteger()
    runBlocking(Dispatchers.Default + CopyableProbe(merges)) {
      val inherited = coroutineContext[CopyableProbe]
      withContext(MDCContext()) {
        check(coroutineContext[CopyableProbe] === inherited)
        check(merges.get() == 0) { "Inherited element was merged with itself" }
      }
      val replacement = CopyableProbe(merges)
      withContext(replacement) {
        check(coroutineContext[CopyableProbe] === replacement)
        check(merges.get() == 1) { "Explicit replacement must be merged exactly once" }
      }
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
  private class CopyableProbe(private val merges: AtomicInteger) : CopyableThreadContextElement<Unit> {
    companion object Key : CoroutineContext.Key<CopyableProbe>
    override val key: CoroutineContext.Key<CopyableProbe> get() = Key
    override fun updateThreadContext(context: CoroutineContext) {}
    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {}
    override fun copyForChild(): CopyableProbe = CopyableProbe(merges)
    override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext {
      check(overwritingElement !== this)
      merges.incrementAndGet()
      return overwritingElement
    }
  }

  @Trace
  override fun scopedSpanSurvivesSuspension(mdc: Boolean, differentDispatcher: Boolean): Int = super.scopedSpanSurvivesSuspension(mdc, differentDispatcher)

  @Trace
  fun tracedAcrossFlows(withModifiedContext: Boolean): Int = runTest {
    val producer = flow<Int> {
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
    val f = flow<Int> {
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
  override fun structuredChildrenOutliveSeedSpan(): Int = super.structuredChildrenOutliveSeedSpan()

  @Trace
  override fun structuredChildrenOfScopedCoroutines(suspendScope: Boolean): Int = super.structuredChildrenOfScopedCoroutines(suspendScope)

  @Trace
  override fun childWithReplacedJob(supervisor: Boolean): Int = super.childWithReplacedJob(supervisor)

  @Trace
  override fun structuredChildOfCancelledCoroutine(): Int = super.structuredChildOfCancelledCoroutine()

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
