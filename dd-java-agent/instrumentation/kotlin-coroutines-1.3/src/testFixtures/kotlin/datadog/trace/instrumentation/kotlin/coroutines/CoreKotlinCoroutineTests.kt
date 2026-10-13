package datadog.trace.instrumentation.kotlin.coroutines

import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

abstract class CoreKotlinCoroutineTests(private val dispatcher: CoroutineDispatcher) {

  @Trace
  open fun tracePreventedByCancellation(): Int {
    kotlin.runCatching {
      runTest {
        tracedChild("preLaunch")

        launch(jobName("errors"), start = CoroutineStart.UNDISPATCHED) {
          throw Exception("Child Error")
        }

        yield()

        tracedChild("postLaunch")
      }
    }

    return 2
  }

  @Trace
  open fun tracedAcrossThreadsWithNested(): Int = runTest {
    val goodDeferred = async(jobName("goodDeferred")) { 1 }

    launch(jobName("root")) {
      goodDeferred.await()
      launch(jobName("nested")) {
        tracedChild("nested")
      }
    }

    2
  }

  @Trace
  open fun traceWithDeferred(): Int = runTest {
    val keptPromise = CompletableDeferred<Boolean>()
    val brokenPromise = CompletableDeferred<Boolean>()
    val afterPromise = async(jobName("afterPromise")) {
      keptPromise.await()
      tracedChild("keptPromise")
    }
    val afterPromise2 = async(jobName("afterPromise2")) {
      keptPromise.await()
      tracedChild("keptPromise2")
    }
    val failedAfterPromise = async(jobName("failedAfterPromise")) {
      brokenPromise
        .runCatching { await() }
        .onFailure { tracedChild("brokenPromise") }
    }

    launch(jobName("future1")) {
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
  open fun tracedWithDeferredFirstCompletions(): Int = runTest {
    val children = listOf(
      async(jobName("timeout1")) {
        tracedChild("timeout1")
        false
      },
      async(jobName("timeout2")) {
        tracedChild("timeout2")
        false
      },
      async(jobName("timeout3")) {
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

  /**
   * --- First job starts -------------------------- First job completes ---
   *
   * -------------------- Second job starts ---------------------------- Second job completes ---
   */
  @Trace
  open fun tracedWithSuspendingCoroutines(): Int = runTest {
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
      async(jobName("first")) {
        beforeFirstJobStartedMutex.lock()
        childSpan("first-span").activateAndUse {
          afterFirstJobStartedMutex.unlock()
          beforeFirstJobCompletedMutex.lock()
        }
        afterFirstJobCompletedMutex.unlock()
      }.run(jobs::add)

      // this coroutine starts after the first one and completes after the first one
      async(jobName("second")) {
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

  @Trace
  open fun tracedWithLazyStarting(): Int = runTest {
    val spans = AtomicInteger(1)
    val jobs = mutableListOf<Deferred<Unit>>()

    childSpan("top-level").activateAndUse {
      spans.incrementAndGet()
      async(jobName("first"), CoroutineStart.LAZY) {
        childSpan("first-span").activateAndUse {
          spans.incrementAndGet()
        }
        delay(1)
      }.run(jobs::add)

      async(jobName("second"), CoroutineStart.LAZY) {
        childSpan("second-span").activateAndUse {
          spans.incrementAndGet()
        }
        delay(1)
      }.run(jobs::add)
    }

    jobs[0].start()
    childSpan("lazy-start").activateAndUse {
      spans.incrementAndGet()
      jobs[1].start()
    }
    jobs.awaitAll()

    spans.get()
  }

  open fun withNoTraceParentSpan(lazy: Boolean, throwing: Boolean): Int {
    val spans = AtomicInteger(0)
    try {
      runTest {
        createAndWaitForCoroutines(lazy, throwing, spans)
        spans.get()
      }
    } catch (_: Exception) {
    }
    return spans.get()
  }

  private suspend fun createAndWaitForCoroutines(lazy: Boolean = false, throwing: Boolean = false, spans: AtomicInteger) = coroutineScope {
    val jobs = mutableListOf<Deferred<Unit>>()
    val start = if (lazy) CoroutineStart.LAZY else CoroutineStart.DEFAULT

    childSpan("top-level").activateAndUse {
      spans.incrementAndGet()
      async(jobName("first"), start) {
        childSpan("first-span").activateAndUse {
          spans.incrementAndGet()
          if (throwing) {
            throw IllegalStateException("first")
          }
          delay(1)
        }
      }.run(jobs::add)

      async(jobName("second"), start) {
        childSpan("second-span").activateAndUse {
          spans.incrementAndGet()
          if (throwing) {
            throw IllegalStateException("second")
          }
          delay(1)
        }
      }.run(jobs::add)
    }

    if (lazy) {
      jobs.forEach { it.start() }
    }

    jobs.forEach {
      try {
        it.await()
      } catch (_: Exception) {
      }
    }
  }

  open fun withNoParentSpan(lazy: Boolean): Int = runTest {
    val jobs = mutableListOf<Deferred<Unit>>()
    val start = if (lazy) CoroutineStart.LAZY else CoroutineStart.DEFAULT

    async(jobName("first"), start) {
      childSpan("first-span").activateAndUse {
        delay(1)
      }
    }.run(jobs::add)

    async(jobName("second"), start) {
      childSpan("second-span").activateAndUse {
        delay(1)
      }
    }.run(jobs::add)

    if (lazy) {
      jobs.forEach { it.start() }
    }
    jobs.awaitAll()

    2
  }

  open fun withParentSpanAndOnlyCanceled(): Int = runTest {
    val jobs = mutableListOf<Deferred<Unit>>()
    val start = CoroutineStart.LAZY

    childSpan("top-level").activateAndUse {
      async(jobName("first"), start) {
        childSpan("first-span").activateAndUse {
          delay(1)
        }
      }.run(jobs::add)

      async(jobName("second"), start) {
        childSpan("second-span").activateAndUse {
          delay(1)
        }
      }.run(jobs::add)
    }

    jobs.forEach {
      try {
        it.cancelAndJoin()
      } catch (_: CancellationException) {
      }
    }

    1
  }

  @Trace
  open fun traceAfterTimeout(): Int = runTest {
    childSpan("1-before-timeout").activateAndUse {
      delay(10)
    }
    withTimeout(50) {
      childSpan("2-inside-timeout").activateAndUse {
        delay(10)
      }
    }
    childSpan("3-after-timeout").activateAndUse {
      delay(10)
    }
    childSpan("4-after-timeout-2").activateAndUse {
      delay(10)
    }
    childSpan("5-after-timeout-3").activateAndUse {
      delay(10)
    }

    6
  }

  @Trace
  open fun traceAfterDelay(): Int = runTest {
    tracedChild("before-process")

    val inputs = listOf("a", "b", "c")

    coroutineScope {
      inputs.map { data ->
        async {
          upload(data)
        }
      }.awaitAll().map {
        tracedChild("process-$it")
        encrypt(it)
      }
    }

    tracedChild("after-process")

    6
  }

  private suspend fun upload(data: String): String {
    delay(100)
    return "url-$data"
  }

  private suspend fun encrypt(message: String): String {
    delay(100)
    return "encrypted-$message"
  }

  @Trace
  protected open fun tracedChild(opName: String) {
    activeSpan().setSpanName(opName)
  }

  protected fun childSpan(opName: String): AgentSpan = get().buildSpan("kotlin_coroutine", opName)
    .withResourceName("coroutines-test-span")
    .start()

  protected fun jobName(jobName: String) = CoroutineName(jobName)

  protected suspend fun AgentSpan.activateAndUse(block: suspend () -> Unit) {
    try {
      val scope = get().activateManualSpan(this)
      try {
        block()
      } finally {
        scope.close()
      }
    } finally {
      finish()
    }
  }

  protected fun <T> runTest(asyncPropagation: Boolean = true, block: suspend CoroutineScope.() -> T): T {
    setAsyncPropagationEnabled(asyncPropagation)
    return runBlocking(jobName("test") + dispatcher, block = block)
  }

  // The following tests start a detached coroutine that outlives the method's root span. Its
  // children wait on a gate that is only released by finishDetached(), after the root span has
  // finished, so only coroutine continuations can keep the trace open until the children finish.

  private val gate = CompletableDeferred<Unit>()
  private var detached: Job? = null

  /** Releases the children of the detached coroutine and waits for it to complete. */
  fun finishDetached() {
    gate.complete(Unit)
    runBlocking { detached?.join() }
  }

  /** Cancels the detached coroutine and waits for it to complete. */
  fun cancelDetached() {
    detached?.cancel()
    runBlocking { detached?.join() }
  }

  /** Children launched with their parent's context, from a span the parent finishes early. */
  @Trace
  open fun structuredChildrenOutliveSeedSpan(): Int {
    runTest {
      detached = launch(Job() + jobName("detached")) {
        childSpan("seed").activateAndUse {
          launch(coroutineContext + jobName("child")) {
            gate.await()
            childSpan("child").activateAndUse {
              launch(coroutineContext + jobName("grandchild")) {
                childSpan("grandchild").finish()
              }
            }
          }
        }
      }
    }
    return 4
  }

  /** Children launched with the context of scoped coroutines inside their parent. */
  @Trace
  open fun structuredChildrenOfScopedCoroutines(): Int {
    runTest {
      detached = launch(Job() + jobName("detached")) {
        childSpan("seed").activateAndUse {
          coroutineScope {
            launch(coroutineContext + jobName("scope-child")) {
              gate.await()
              childSpan("scope-child").finish()
            }
          }
          withContext(jobName("with-context")) {
            launch(coroutineContext + jobName("context-child")) {
              gate.await()
              childSpan("context-child").finish()
            }
          }
        }
      }
    }
    return 4
  }

  /** A child launched with its parent's context, when the parent started without a trace. */
  open fun structuredChildOfUntracedCoroutine(): Int {
    runTest {
      detached = launch(Job() + jobName("detached")) {
        childSpan("root").activateAndUse {
          val started = CompletableDeferred<Unit>()
          launch(coroutineContext + jobName("child")) {
            started.complete(Unit)
            gate.await()
            childSpan("child").finish()
          }
          // the child's first dispatch can keep the trace open, so finish the root after it
          started.await()
        }
      }
    }
    return 2
  }

  /** A child launched with its parent's context but a different job, so not a structured child. */
  @Trace
  open fun childWithReplacedJob(supervisor: Boolean): Int {
    runTest {
      val job = if (supervisor) SupervisorJob() else Job()
      val started = CompletableDeferred<Unit>()
      detached = launch(coroutineContext + job + jobName("detached")) {
        started.complete(Unit)
        gate.await()
        childSpan("child").finish()
      }
      // the child's first dispatch can keep the trace open, so finish the root after it
      started.await()
    }
    return 2
  }

  /** A structured child that only adds its span while its cancelled parent waits for it. */
  @Trace
  open fun structuredChildOfCancelledCoroutine(): Int {
    runTest {
      val started = CompletableDeferred<Unit>()
      detached = launch(Job() + jobName("detached")) {
        launch(coroutineContext + jobName("child")) {
          try {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
          } finally {
            withContext(NonCancellable) {
              delay(50)
              childSpan("cleanup").finish()
            }
          }
        }
      }
      // a child cancelled before it starts never runs its finally block
      started.await()
    }
    return 2
  }
}
