package datadog.trace.instrumentation.kotlin.coroutines

import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
abstract class CoreKotlinCoroutineTests(
  private val dispatcher: CoroutineDispatcher,
) {
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
  open fun tracedAcrossThreadsWithNested(): Int =
    runTest {
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
  open fun traceWithDeferred(): Int =
    runTest {
      val keptPromise = CompletableDeferred<Boolean>()
      val brokenPromise = CompletableDeferred<Boolean>()
      val afterPromise =
        async(jobName("afterPromise")) {
          keptPromise.await()
          tracedChild("keptPromise")
        }
      val afterPromise2 =
        async(jobName("afterPromise2")) {
          keptPromise.await()
          tracedChild("keptPromise2")
        }
      val failedAfterPromise =
        async(jobName("failedAfterPromise")) {
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
  open fun tracedWithDeferredFirstCompletions(): Int =
    runTest {
      val children =
        listOf(
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
          },
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
  open fun tracedWithSuspendingCoroutines(): Int =
    runTest {
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
  open fun tracedWithLazyStarting(): Int =
    runTest {
      val spans = AtomicInteger(1)
      val jobs = mutableListOf<Deferred<Unit>>()

      childSpan("top-level").activateAndUse {
        spans.incrementAndGet()
        async(jobName("first"), CoroutineStart.LAZY) {
          childSpan("first-span").activateAndUse {
            spans.incrementAndGet()
            delay(1)
          }
        }.run(jobs::add)

        async(jobName("second"), CoroutineStart.LAZY) {
          childSpan("second-span").activateAndUse {
            spans.incrementAndGet()
            delay(1)
          }
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

  open fun withNoTraceParentSpan(
    lazy: Boolean,
    throwing: Boolean,
  ): Int {
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

  private suspend fun createAndWaitForCoroutines(
    lazy: Boolean = false,
    throwing: Boolean = false,
    spans: AtomicInteger,
  ) = coroutineScope {
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

  open fun withNoParentSpan(lazy: Boolean): Int =
    runTest {
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

  open fun withParentSpanAndOnlyCanceled(): Int =
    runTest {
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
  open fun traceAfterTimeout(): Int =
    runTest {
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
  open fun traceAfterDelay(): Int =
    runTest {
      tracedChild("before-process")

      val inputs = listOf("a", "b", "c")

      coroutineScope {
        inputs
          .map { data ->
            async {
              upload(data)
            }
          }.awaitAll()
          .map {
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

  protected fun childSpan(opName: String): AgentSpan =
    get()
      .buildSpan(opName)
      .withResourceName("coroutines-test-span")
      .start()

  protected fun jobName(jobName: String) = CoroutineName(jobName)

  protected suspend fun AgentSpan.activateAndUse(block: suspend () -> Unit) {
    try {
      get().activateManualSpan(this).use {
        block()
      }
    } finally {
      finish()
    }
  }

  protected fun <T> runTest(
    asyncPropagation: Boolean = true,
    block: suspend CoroutineScope.() -> T,
  ): T {
    setAsyncPropagationEnabled(asyncPropagation)
    return runBlocking(jobName("test") + dispatcher, block = block)
  }
}
