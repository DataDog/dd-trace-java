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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.slf4j.MDC
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

abstract class CoreKotlinCoroutineTests(private val dispatcher: CoroutineDispatcher) {

  @Trace
  open fun scopedSpanSurvivesSuspension(mdc: Boolean, differentDispatcher: Boolean): Int {
    setAsyncPropagationEnabled(true)
    val outer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val inner = if (differentDispatcher) Executors.newSingleThreadExecutor().asCoroutineDispatcher() else outer
    try {
      runBlocking(outer + MDCContext(mapOf("request-id" to "request"))) {
        val outerContext = coroutineContext
        childSpan("seed").activateAndUse {
          val requested = (if (mdc) MDCContext() else CoroutineName("scoped")) +
            (if (differentDispatcher) inner else EmptyCoroutineContext)
          withContext(requested) {
            check(MDC.get("request-id") == "request")
            val innerContext = coroutineContext
            val scoped = childSpan("scoped-span")
            scoped.activateAndUse {
              // Queue behind both dispatchers' restores before resuming the scoped body.
              suspendCoroutine<Unit> { continuation ->
                outer.dispatch(
                  outerContext,
                  Runnable {
                    inner.dispatch(innerContext, Runnable { continuation.resume(Unit) })
                  }
                )
              }
              check(MDC.get("request-id") == "request")
              check(activeSpan() === scoped) { "Scoped span was lost across suspension" }
              launch { childSpan("child").finish() }.join()
            }
          }
          childSpan("after-context").activateAndUse {
            delay(10)
            childSpan("after-child").finish()
          }
        }
      }
    } finally {
      if (inner !== outer) inner.close()
      outer.close()
    }
    return 6
  }

  fun emptyWithContextPreservesFastPath() {
    val updates = AtomicInteger()
    val restores = AtomicInteger()
    val hook = object :
      AbstractCoroutineContextElement(object : CoroutineContext.Key<ThreadContextElement<Unit>> {}),
      ThreadContextElement<Unit> {
      override fun updateThreadContext(context: CoroutineContext) {
        updates.incrementAndGet()
      }
      override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
        restores.incrementAndGet()
      }
    }
    runBlocking(hook) {
      val original = coroutineContext
      val beforeUpdates = updates.get()
      val beforeRestores = restores.get()
      withContext(EmptyCoroutineContext) {
        check(updates.get() == beforeUpdates)
        check(restores.get() == beforeRestores)
        // withContext creates a scoped Job even on its unchanged-context fast path.
        check(coroutineContext[hook.key] === original[hook.key])
      }
      check(updates.get() == beforeUpdates)
      check(restores.get() == beforeRestores)
    }
  }

  fun flowWithLocallyActivatedRoot(unchangedContext: Boolean, suspendFlow: Boolean) = runTest {
    childSpan("flow-root").activateAndUse {
      val original = coroutineContext
      flow<Int> {
        if (unchangedContext) {
          check(kotlin.coroutines.coroutineContext === original) { "flowOn lost the unchanged-context fast path" }
        }
        if (suspendFlow) delay(10)
        childSpan("flow-child").finish()
        emit(1)
      }.flowOn(if (unchangedContext) jobName("test") else jobName("producer")).collect {
        if (suspendFlow) delay(10)
        childSpan("collect-child").finish()
      }
      childSpan("after-flow").finish()
    }
  }

  fun flowEmissionInContext(kind: String, suspendFlow: Boolean) = runTest {
    childSpan("flow-root").activateAndUse {
      withContext(jobName("outer")) {
        flow<Int> {
          val inherited = kotlin.coroutines.coroutineContext
          if (kind == "launch") {
            coroutineScope { launch { emit(1) }.join() }
          } else {
            val requested = when (kind) {
              "full" -> inherited
              "without-job" -> inherited.minusKey(Job)
              "dispatcher" -> inherited[ContinuationInterceptor]!!
              "name" -> inherited[CoroutineName]!!
              "equal-name" -> jobName("outer")
              "changed-name" -> jobName("changed")
              "changed-dispatcher" -> Dispatchers.IO
              "mdc" -> MDCContext()
              else -> error("Unknown emission context: $kind")
            }
            withContext(requested) {
              val span = childSpan("emission")
              span.activateAndUse {
                if (suspendFlow) delay(10)
                check(activeSpan() === span) { "Emission span was lost across suspension" }
                emit(1)
                check(activeSpan() === span) { "Collector changed the active emission span" }
              }
            }
          }
        }.collect {
          if (suspendFlow) delay(10)
          childSpan("collect-child").finish()
        }
      }
      childSpan("after-flow").finish()
    }
  }

  fun flowWithFailedEmission() = runTest {
    withContext(jobName("outer")) {
      flow<Int> {
        withContext(kotlin.coroutines.coroutineContext) {
          try {
            emit(1)
          } catch (expected: IllegalArgumentException) {
            check(expected.message == "collector failure")
          }
          emit(2)
        }
      }.collect { throw IllegalArgumentException("collector failure") }
    }
  }

  fun equalWithContextSpanSurvivesSuspension() {
    val singleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
      runBlocking(singleThread + jobName("same")) {
        childSpan("root").activateAndUse {
          withContext(jobName("same")) {
            childSpan("scoped").activateAndUse {
              val span = activeSpan()
              delay(10)
              check(activeSpan() === span) { "Equal-context scoped span was lost across suspension" }
              childSpan("child").finish()
            }
          }
        }
      }
    } finally {
      singleThread.close()
    }
  }

  fun withContextKeepsEarlyFinishedRootOpen() {
    runTest {
      childSpan("early-root").activateAndUse {
        val root = activeSpan()
        withContext(MDCContext()) {
          root.finish()
          launch { childSpan("late-child").finish() }.join()
        }
      }
    }
  }

  fun withContextWithReplacedJobKeepsEarlyFinishedRootOpen(nonCancellable: Boolean) = runTest {
    childSpan("early-root").activateAndUse {
      val root = activeSpan()
      // The caller captures the root before withContext replaces its Job.
      launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(if (nonCancellable) NonCancellable else Job()) {
          root.finish()
          delay(10)
          check(activeSpan() === root) { "Replacing Job lost the root across suspension" }
          childSpan("late-child").finish()
        }
        childSpan("after-context").finish()
      }.join()
    }
  }

  fun withContextChildInheritsEarlyFinishedRoot() = runTest {
    childSpan("early-root").activateAndUse {
      val root = activeSpan()
      launch(start = CoroutineStart.UNDISPATCHED) {
        // Only the caller's continuation can keep the root open across the fresh context merge.
        root.finish()
        withContext(MDCContext()) {
          delay(10)
          launch(coroutineContext + jobName("late-child")) {
            delay(10)
            check(activeSpan() === root) { "Child lost the inherited finished root" }
            childSpan("late-child").finish()
          }.join()
        }
        childSpan("after-context").finish()
      }.join()
    }
  }

  fun launchWithCancelledParent() {
    val queuedDispatcher = object : CoroutineDispatcher() {
      var pending: Runnable? = null

      override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(pending == null) { "Unexpected second dispatch" }
        pending = block
      }
    }
    val parent = Job()
    parent.cancel()

    val child = CoroutineScope(parent + queuedDispatcher).launch {
      error("A child of an already-cancelled parent must not execute")
    }

    checkNotNull(queuedDispatcher.pending) { "Cancellation was not dispatched" }.run()
    check(child.isCompleted && child.isCancelled) { "Child did not complete cancellation" }
  }

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

  /** A structured child launched after a scoped coroutine has completed in its parent. */
  open fun structuredChildAfterScopedCoroutine(scoped: String): Int {
    runTest {
      childSpan("root").activateAndUse {
        val seedFinished = CompletableDeferred<Unit>()
        detached = launch(Job() + jobName("detached")) {
          childSpan("seed").activateAndUse {
            when (scoped) {
              "none" -> Unit
              "withContext" -> withContext(jobName("scoped")) {}
              "coroutineScope" -> coroutineScope {}
              "withTimeout" -> withTimeout(30_000) {}
              else -> error("Unknown scoped coroutine: $scoped")
            }
            launch(coroutineContext + jobName("child")) {
              gate.await()
              childSpan("child").finish()
            }
          }
          seedFinished.complete(Unit)
        }
        // Finish both spans before opening the child's gate, regardless of dispatcher timing.
        seedFinished.await()
      }
    }
    return 3
  }

  /** Children launched with the context of scoped coroutines inside their parent. */
  @Trace
  open fun structuredChildrenOfScopedCoroutines(suspendScope: Boolean): Int {
    if (!suspendScope) gate.complete(Unit)
    runTest {
      detached = launch(Job() + jobName("detached"), start = CoroutineStart.UNDISPATCHED) {
        childSpan("seed").activateAndUse {
          // Enter the child inline so the closed gate always suspends this scope.
          coroutineScope {
            launch(coroutineContext + jobName("scope-child"), start = CoroutineStart.UNDISPATCHED) {
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
