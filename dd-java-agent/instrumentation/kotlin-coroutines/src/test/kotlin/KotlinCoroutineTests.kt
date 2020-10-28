
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.toChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class KotlinCoroutineTests(private val dispatcher: CoroutineDispatcher) {
  val tracer: AgentTracer.TracerAPI = AgentTracer.get()

  @Trace
  fun tracedAcrossChannels(): Int = runTest {
    val producer = produce {
      repeat(3) {
        tracedChild("produce_$it")
        send(it)
      }
    }

    val actor = actor<Int> {
      consumeEach {
        tracedChild("consume_$it")
      }
    }

    producer.toChannel(actor)
    actor.close()

    7
  }

  @Trace
  fun tracePreventedByCancellation(): Int {

    kotlin.runCatching {
      runTest {
        tracedChild("preLaunch")

        launch(start = CoroutineStart.UNDISPATCHED) {
          throw Exception("Child Error")
        }

        yield()

        tracedChild("postLaunch")
      }
    }

    return 2
  }

  @Trace
  fun tracedAcrossThreadsWithNested(): Int = runTest {
    val goodDeferred = async { 1 }

    launch {
      goodDeferred.await()
      launch { tracedChild("nested") }
    }

    2
  }

  @Trace
  fun traceWithDeferred(): Int = runTest {

    val keptPromise = CompletableDeferred<Boolean>()
    val brokenPromise = CompletableDeferred<Boolean>()
    val afterPromise = async {
      keptPromise.await()
      tracedChild("keptPromise")
    }
    val afterPromise2 = async {
      keptPromise.await()
      tracedChild("keptPromise2")
    }
    val failedAfterPromise = async {
      brokenPromise
        .runCatching { await() }
        .onFailure { tracedChild("brokenPromise") }
    }

    launch {
      tracedChild("future1")
      keptPromise.complete(true)
      brokenPromise.completeExceptionally(IllegalStateException())
    }

    listOf(afterPromise, afterPromise2, failedAfterPromise).awaitAll()

    5
  }

  /**
   * @return Number of expected spans in the trace
   */
  @Trace
  fun tracedWithDeferredFirstCompletions(): Int = runTest {

    val children = listOf(
      async {
        tracedChild("timeout1")
        false
      },
      async {
        tracedChild("timeout2")
        false
      },
      async {
        tracedChild("timeout3")
        true
      }
    )

    withTimeout(TimeUnit.SECONDS.toMillis(30)) {
      select<Boolean> {
        children.forEach { child ->
          child.onAwait { it }
        }
      }
    }

    4
  }

  fun launchConcurrentSuspendFunctions(numIters: Int) {
    runBlocking {
      for (i in 0 until numIters) {
        GlobalScope.launch {
          a(i.toLong())
        }
        GlobalScope.launch {
          b(i.toLong())
        }
      }
    }
  }

  suspend fun a(iter: Long) {
    val span = tracer.buildSpan("a").withTag("iter", iter).start()
    val scope = tracer.activateSpan(span, ScopeSource.INSTRUMENTATION)
    delay(10)
    a2(iter)
    scope.close()
    span.finish()
  }
  suspend fun a2(iter: Long) {
    val span = tracer.buildSpan("a2").withTag("iter", iter).start()
    val scope = tracer.activateSpan(span, ScopeSource.INSTRUMENTATION)
    delay(10)
    scope.close()
    span.finish()
  }
  suspend fun b(iter: Long) {
    val span = tracer.buildSpan("b").withTag("iter", iter).start()
    val scope = tracer.activateSpan(span, ScopeSource.INSTRUMENTATION)
    delay(10)
    b2(iter)
    scope.close()
    span.finish()
  }
  suspend fun b2(iter: Long) {
    val span = tracer.buildSpan("b2").withTag("iter", iter).start()
    val scope = tracer.activateSpan(span, ScopeSource.INSTRUMENTATION)
    delay(10)
    scope.close()
    span.finish()
  }

  @Trace
  fun tracedChild(opName: String) {
    activeSpan().setSpanName(opName)
  }

  private fun <T> runTest(asyncPropagation: Boolean = true, block: suspend CoroutineScope.() -> T): T {
    activeScope().setAsyncPropagation(asyncPropagation)
    return runBlocking(dispatcher, block = block)
  }
}
