import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import datadog.trace.bootstrap.instrumentation.api.ScopeSource.INSTRUMENTATION
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.TimeUnit

@SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
class KotlinCoroutineTests(private val dispatcher: CoroutineDispatcher) {

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

  @Trace
  fun tracedChild(opName: String) {
    activeSpan().setSpanName(opName)
  }

  fun childSpan(opName: String): AgentSpan = get().buildSpan(opName)
    .withResourceName("coroutines-test-span")
    .start()

  /**
   * --- First job starts -------------------------- First job completes ---
   *
   * -------------------- Second job starts ---------------------------- Second job completes ---
   */
  @Trace
  fun tracedWithSuspendingCoroutines(): Int {
    fun jobContext(jobName: String) = CoroutineName(jobName)

    return runTest {
      val jobs = mutableListOf<Deferred<Unit>>()

      val beforeFirstJobStartedMutex = Mutex(locked = true)
      val afterFirstJobStartedMutex = Mutex(locked = true)

      val beforeFirstJobCompletedMutex = Mutex(locked = true)
      val afterFirstJobCompletedMutex = Mutex(locked = true)

      childSpan("top-level").activateAndUse {
        childSpan("synchronous-child").activateAndUse {
          delay(5)
        }

        // this coroutine starts before the second one starts and completes before the second one
        async(jobContext("first")) {
          beforeFirstJobStartedMutex.lock()
          childSpan("first-span").activateAndUse {
            afterFirstJobStartedMutex.unlock()
            beforeFirstJobCompletedMutex.lock()
          }
          afterFirstJobCompletedMutex.unlock()
        }.run(jobs::add)

        // this coroutine starts after the first one and completes after the first one
        async(jobContext("second")) {
          afterFirstJobStartedMutex.withLock {
            childSpan("second-span").activateAndUse {
              beforeFirstJobCompletedMutex.unlock()
              afterFirstJobCompletedMutex.lock()
            }
          }
        }.run(jobs::add)
      }
      beforeFirstJobStartedMutex.unlock()

      jobs.awaitAll()

      5
    }
  }

  private suspend fun AgentSpan.activateAndUse(block: suspend () -> Unit) {
    get().activateSpan(this, INSTRUMENTATION).use {
      block()
      finish()
    }
  }

  private fun <T> runTest(asyncPropagation: Boolean = true, block: suspend CoroutineScope.() -> T): T {
    activeScope().setAsyncPropagation(asyncPropagation)
    return runBlocking(dispatcher, block = block)
  }
}
