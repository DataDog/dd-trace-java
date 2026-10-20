package datadog.trace.instrumentation.kotlin.coroutines

import datadog.context.Context
import datadog.context.ContextKey
import datadog.context.ContextScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.newCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val TOUCH_KEY = ContextKey.named<Any>("scope-touching")
private val BASE_KEY = ContextKey.named<String>("scope-touching-base")

@OptIn(ExperimentalCoroutinesApi::class)
class StandardSuspensionHandoffTests {
  fun runLazyChildWithInheritedContext(builder: String, automatic: Boolean, tracedStarter: Boolean) = runBlocking {
    val creator = get().buildSpan("kotlin_coroutine", "creator").start()
    val creatorScope = get().activateManualSpan(creator)
    val starter = if (tracedStarter) get().buildSpan("kotlin_coroutine", "starter").ignoreActiveSpan().start() else null
    val worker = Executors.newSingleThreadExecutor()
    try {
      val block: suspend CoroutineScope.() -> Unit = {
        check(activeSpan() === starter) { "Lazy child did not inherit the starting thread's context" }
        delay(1)
        check(activeSpan() === starter) { "Lazy child lost its starting context after suspension" }
      }
      val child = when (builder) {
        "async" -> async(coroutineContext, CoroutineStart.LAZY, block)
        "launch" -> launch(coroutineContext, CoroutineStart.LAZY, block)
        else -> error("Unknown lazy builder: $builder")
      }
      val started = worker.submit {
        // Isolate the starter from executor instrumentation's capture of the creator's context.
        val original = Context.root().swap()
        val scope = starter?.let { get().activateManualSpan(it) }
        try {
          if (automatic) {
            runBlocking {
              if (child is Deferred<*>) child.await() else child.join()
            }
          } else {
            child.start()
          }
        } finally {
          scope?.close()
          original.swap()
        }
      }
      // Let the creator's dispatcher run its child while the starter waits for it.
      withContext(Dispatchers.IO) { started.get(10, SECONDS) }
      child.join()
    } finally {
      worker.shutdownNow()
      check(worker.awaitTermination(5, SECONDS)) { "Lazy starter did not terminate" }
      creatorScope.close()
      creator.finish()
      starter?.finish()
    }
  }

  fun runContextSwapAcrossMerge() {
    runBlocking {
      val parent = get().buildSpan("kotlin_coroutine", "parent").start()
      val parentScope = get().activateManualSpan(parent)
      try {
        val swapped = get().buildSpan("kotlin_coroutine", "swapped").start()
        val previous = swapped.swap()
        try {
          withContext(CoroutineName("nested")) {
            check(activeSpan() === swapped) { "Context merge discarded the explicitly swapped span" }
            get().buildSpan("kotlin_coroutine", "child").start().finish()
          }
          check(activeSpan() === swapped) { "Context merge did not restore the swapped span" }
        } finally {
          previous.swap()
          swapped.finish()
        }
        check(activeSpan() === parent) { "Balanced swap did not restore the parent" }
      } finally {
        parentScope.close()
        parent.finish()
      }
    }
  }

  fun runScopedChildWait(kind: String, closeInside: Boolean, outcome: String) {
    val workers = Executors.newFixedThreadPool(2)
    val childWorkers = Executors.newSingleThreadExecutor()
    val dispatcher = workers.asCoroutineDispatcher()
    val childDispatcher = childWorkers.asCoroutineDispatcher()
    val restoring = CountDownLatch(1)
    val released = CountDownLatch(1)
    val childStarted = CountDownLatch(1)
    val expected = when (outcome) {
      "return" -> null
      "throw" -> IllegalStateException("scoped body failed")
      "cancel" -> CancellationException("scoped body cancelled")
      else -> error("Unknown completion: $outcome")
    }
    val before = RestoreGate(restoring, released)
    val after = RestoreGate(restoring, released)
    val context = CoroutineScope(dispatcher).newCoroutineContext(before) + after
    val parent = get().buildSpan("kotlin_coroutine", "parent").start()
    val parentScope = get().activateManualSpan(parent)
    try {
      runBlocking(context) {
        suspend fun closeCapturedScope() {
          val transient = get().buildSpan("kotlin_coroutine", "closed").start()
          val scope = get().activateManualSpan(transient)
          try {
            suspendCoroutine<Unit> { it.resume(Unit) }
          } finally {
            scope.close()
            transient.finish()
          }
          check(activeSpan() === parent) { "Inline completion did not restore parent" }
        }
        if (!closeInside) closeCapturedScope()
        val block: suspend CoroutineScope.() -> Unit = {
          if (closeInside) closeCapturedScope()
          launch(childDispatcher) {
            childStarted.countDown()
            check(restoring.await(10, SECONDS)) { "Source did not enter restoration" }
          }
          check(childStarted.await(10, SECONDS)) { "Child did not start" }
          if (expected != null) throw expected
          Unit
        }
        try {
          try {
            when (kind) {
              "scope" -> coroutineScope(block)
              "supervisor" -> supervisorScope(block)
              "context" -> withContext(CoroutineName("nested"), block)
              "timeout" -> withTimeout(10000, block)
              else -> error("Unknown scoped builder: $kind")
            }
            check(expected == null) { "Scoped failure was swallowed" }
          } catch (failure: Exception) {
            check(expected != null && (failure === expected || failure.cause === expected)) {
              "Unexpected scoped failure: $failure"
            }
          }
          check(restoring.count == 0L && released.count == 1L) { "Source restoration did not overlap resumption" }
          check(before.updates.get() >= 2 && after.updates.get() >= 2) { "Parent did not resume" }
          check(activeSpan() === parent) { "Child wait resumed ${activeSpan()?.operationName} instead of parent" }
          get().buildSpan("kotlin_coroutine", "after-child").start().finish()
        } finally {
          released.countDown()
        }
      }
    } finally {
      released.countDown()
      restoring.countDown()
      parentScope.close()
      parent.finish()
      dispatcher.close()
      childDispatcher.close()
      check(workers.awaitTermination(10, SECONDS))
      check(childWorkers.awaitTermination(10, SECONDS))
    }
  }

  fun runReplayedRestoration() {
    val interceptor = object : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
      override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = continuation
    }
    var saved: Continuation<Unit>? = null
    var failure: Throwable? = null
    var completed = false
    val parent = get().buildSpan("kotlin_coroutine", "parent").start()
    val parentScope = get().activateManualSpan(parent)
    val handler = CoroutineExceptionHandler { _, error -> failure = error }
    try {
      val job = CoroutineScope(interceptor + handler).launch {
        withContext(CoroutineName("nested")) {
          suspendCoroutine<Unit> { saved = it }
        }
        check(activeSpan() === parent) { "Caller continuation lost its context" }
        completed = true
      }
      check(saved != null) { "The test did not suspend" }
      // Resume without a dispatcher installing context. Kotlin must replay its saved restore token.
      val original = Context.root().swap()
      try {
        saved!!.resume(Unit)
        check(failure == null) { "Coroutine failed: $failure" }
        check(completed && job.isCompleted) { "Caller continuation did not complete" }
        check(activeSpan() === parent) { "Replayed restoration lost the caller thread context" }
      } finally {
        original.swap()
      }
    } finally {
      parentScope.close()
      parent.finish()
    }
  }

  fun runEarlyDispatcherChange(openAfter: Boolean, outcome: String, delayedRestore: Boolean) {
    val sourceWorkers = Executors.newSingleThreadExecutor()
    val destinationWorkers = Executors.newSingleThreadExecutor()
    val source = sourceWorkers.asCoroutineDispatcher()
    val restoring = CountDownLatch(1)
    val released = CountDownLatch(1)
    val destination = object : CoroutineDispatcher() {
      override fun dispatch(context: CoroutineContext, block: Runnable) {
        // Run the real destination task, including restoration, before source getResult can execute.
        val task = destinationWorkers.submit(block)
        if (delayedRestore) {
          check(restoring.await(10, SECONDS)) { "Destination did not reach restoration" }
        } else {
          task.get(10, SECONDS)
        }
      }
    }
    val context: CoroutineContext = if (delayedRestore) {
      // Gate both restoration orders: Kotlin 1.3 restores forwards, newer versions backwards.
      val before = DestinationRestoreGate(destination, restoring, released)
      val after = DestinationRestoreGate(destination, restoring, released)
      CoroutineScope(source).newCoroutineContext(before) + after
    } else {
      source
    }
    val expected = when (outcome) {
      "return" -> null
      "throw" -> IllegalStateException("destination failure")
      "cancel" -> CancellationException("destination cancellation")
      else -> error("Unknown completion: $outcome")
    }
    try {
      runBlocking(context) {
        var span = if (openAfter) null else get().buildSpan("kotlin_coroutine", "manual").start()
        var scope = span?.let { get().activateManualSpan(it) }
        try {
          try {
            withContext(destination) {
              if (!openAfter) {
                check(activeSpan() === span) { "Destination lost the caller scope before getResult" }
              }
              if (expected != null) throw expected
            }
            check(expected == null) { "Destination failure was swallowed" }
          } catch (failure: Exception) {
            check(failure === expected) { "Unexpected destination failure: $failure" }
          }
          if (openAfter) {
            span = get().buildSpan("kotlin_coroutine", "manual").start()
            scope = get().activateManualSpan(span)
          }
          check(activeSpan() === span) { "Caller scope was lost on synchronous completion" }
          suspendCancellableCoroutine<Unit> { continuation ->
            // This queue entry cannot run until source restoration returns its worker.
            sourceWorkers.execute { continuation.resume(Unit) }
          }
          check(activeSpan() === span) { "Caller scope was lost after the next suspension" }
          if (delayedRestore) {
            check(restoring.count == 0L && released.count == 1L) { "Destination cleanup was not delayed" }
          }
          get().buildSpan("kotlin_coroutine", "child").start().finish()
        } finally {
          scope?.close()
          span?.finish()
          released.countDown()
        }
      }
    } finally {
      released.countDown()
      source.close()
      destinationWorkers.shutdown()
      check(sourceWorkers.awaitTermination(10, SECONDS))
      check(destinationWorkers.awaitTermination(10, SECONDS))
    }
  }

  fun runFlowOnCollectionThenSuspend() {
    val workers = Executors.newFixedThreadPool(2)
    val dispatcher = workers.asCoroutineDispatcher()
    try {
      runBlocking(dispatcher) {
        // Keeping the dispatcher makes flowOn collect upstream undispatched on this coroutine.
        flow { emit(1) }.flowOn(CoroutineName("producer")).collect { }
        val manual = get().buildSpan("kotlin_coroutine", "manual").start()
        val scope = get().activateManualSpan(manual)
        try {
          repeat(10) { delay(1) }
          check(activeSpan() === manual) { "Suspension after flowOn lost the manual scope" }
        } finally {
          scope.close()
          manual.finish()
        }
      }
    } finally {
      dispatcher.close()
      check(workers.awaitTermination(10, SECONDS))
    }
  }

  fun runNestedContextChange() {
    val workers = Executors.newSingleThreadExecutor()
    val otherWorkers = Executors.newSingleThreadExecutor()
    val dispatcher = workers.asCoroutineDispatcher()
    val otherDispatcher = otherWorkers.asCoroutineDispatcher()
    val childRelease = CountDownLatch(1)
    try {
      runBlocking(dispatcher) {
        withContext(CoroutineName("nested")) { Unit }
        val manual = get().buildSpan("kotlin_coroutine", "manual").start()
        val scope = get().activateManualSpan(manual)
        try {
          coroutineScope {
            launch(otherDispatcher, start = CoroutineStart.ATOMIC) {
              check(childRelease.await(10, SECONDS))
            }
            // The child can finish only after this coroutine returns its worker.
            workers.execute { childRelease.countDown() }
          }
          check(activeSpan() === manual) { "Waiting for the child lost the manual scope" }
        } finally {
          scope.close()
          manual.finish()
        }
      }
    } finally {
      childRelease.countDown()
      dispatcher.close()
      otherDispatcher.close()
      check(workers.awaitTermination(10, SECONDS))
      check(otherWorkers.awaitTermination(10, SECONDS))
    }
  }

  fun runChildScopeIsolation() {
    val workers = Executors.newSingleThreadExecutor()
    val childWorkers = Executors.newSingleThreadExecutor()
    val dispatcher = workers.asCoroutineDispatcher()
    val childDispatcher = childWorkers.asCoroutineDispatcher()
    val parentSuspended = CountDownLatch(1)
    val childContinuation = AtomicReference<Continuation<Unit>>()
    val parent = get().buildSpan("kotlin_coroutine", "parent").start()
    val parentScope = get().activateManualSpan(parent)
    try {
      runBlocking(dispatcher + SuspendGate(parentSuspended)) {
        try {
          suspendCancellableCoroutine<Unit> { parentContinuation ->
            // The child inherits the parent's Datadog element; override the gate so only the parent signals.
            launch(childDispatcher + SuspendGate(CountDownLatch(0))) {
              val child = get().buildSpan("kotlin_coroutine", "child").start()
              val childScope = get().activateManualSpan(child)
              try {
                // Suspend with the child scope still open.
                suspendCancellableCoroutine<Unit> { continuation ->
                  childContinuation.set(continuation)
                  // Queued behind this task, so it runs only after the child has restored its worker.
                  childWorkers.execute {
                    check(parentSuspended.await(10, SECONDS)) { "Parent did not suspend" }
                    parentContinuation.resume(Unit)
                  }
                }
              } finally {
                childScope.close()
                child.finish()
              }
            }
          }
          check(activeSpan() === parent) { "Parent resumed with ${activeSpan()?.operationName} instead of parent" }
        } finally {
          childContinuation.get()?.resume(Unit)
        }
      }
    } finally {
      parentScope.close()
      parent.finish()
      dispatcher.close()
      childDispatcher.close()
      check(workers.awaitTermination(10, SECONDS))
      check(childWorkers.awaitTermination(10, SECONDS))
    }
  }

  /**
   * Runs a coroutine between two elements that attach and close Datadog scopes in their own
   * update/restore. With newer kotlinx versions the element after Datadog closes its scope on the
   * coroutine stack after the suspension has been published, racing with the next worker reading
   * that stack. Returns counts of each kind of violation seen, empty if none.
   */
  fun runScopeTouchingElements(iterations: Int): Map<String, Int> {
    val workers = Executors.newFixedThreadPool(4)
    val resumers = Executors.newSingleThreadExecutor()
    val dispatcher = workers.asCoroutineDispatcher()
    val violations = ConcurrentHashMap<String, AtomicInteger>()
    val record: (String) -> Unit = { kind -> violations.computeIfAbsent(kind) { AtomicInteger() }.incrementAndGet() }
    val before = ScopeTouchingElement("before", record)
    val after = ScopeTouchingElement("after", record)
    val context = CoroutineScope(dispatcher).newCoroutineContext(before) + after
    val base = Context.root().with(BASE_KEY, "base")
    val baseScope = base.attach()
    try {
      runBlocking(context) {
        repeat(iterations) {
          // Suspend with no scope open in the body, so the element's restore pops the coroutine
          // stack itself. Resume straight away so the next worker races that restore.
          suspendCancellableCoroutine<Unit> { continuation ->
            resumers.execute { continuation.resume(Unit) }
          }
          // What Datadog installed for this activation, as seen by the element updated after it.
          val installed = after.below.get()
          when {
            installed === base -> Unit
            installed.get(TOUCH_KEY) != null -> record("element scope leaked from previous worker")
            installed.get(BASE_KEY) == null -> record("coroutine base context lost")
            else -> record("unexpected coroutine context")
          }
        }
      }
    } catch (failure: Throwable) {
      record("coroutine failed: ${failure.javaClass.name}")
    } finally {
      baseScope.close()
      dispatcher.close()
      resumers.shutdown()
      check(workers.awaitTermination(10, SECONDS))
      check(resumers.awaitTermination(10, SECONDS))
    }
    return violations.mapValues { it.value.get() }
  }

  fun runDispatcherChange() {
    val workers = Executors.newSingleThreadExecutor()
    val otherWorkers = Executors.newSingleThreadExecutor()
    val dispatcher = workers.asCoroutineDispatcher()
    val otherDispatcher = otherWorkers.asCoroutineDispatcher()
    val restoring = CountDownLatch(1)
    val released = CountDownLatch(1)
    val before = RestoreGate(restoring, released)
    val after = RestoreGate(restoring, released)
    val context = CoroutineScope(dispatcher).newCoroutineContext(before) + after
    // withContext dispatches before getResult. Delay the destination until getResult has run,
    // while the source's Datadog restoration is still blocked by one of the gates.
    val destinationGate = otherWorkers.submit { check(restoring.await(10, SECONDS)) }
    try {
      runBlocking(context) {
        val manual = get().buildSpan("kotlin_coroutine", "manual").start()
        val scope = get().activateManualSpan(manual)
        try {
          withContext(otherDispatcher) {
            try {
              check(restoring.count == 0L) { "The source did not suspend" }
              check(released.count == 1L) { "The source restoration was not delayed" }
              check(activeSpan() === manual) { "Dispatcher switch lost the manual scope" }
            } finally {
              released.countDown()
            }
          }
        } finally {
          scope.close()
          manual.finish()
        }
      }
      destinationGate.get(10, SECONDS)
      check(before.updates.get() >= 2)
      check(after.updates.get() >= 2)
    } finally {
      released.countDown()
      restoring.countDown()
      dispatcher.close()
      otherDispatcher.close()
      check(workers.awaitTermination(10, SECONDS))
      check(otherWorkers.awaitTermination(10, SECONDS))
    }
  }

  fun run(kind: String, cancelled: Boolean, nested: Boolean, beforeCompletion: Runnable) {
    val workers = Executors.newFixedThreadPool(2)
    val dispatcher = workers.asCoroutineDispatcher()
    val restoring = CountDownLatch(1)
    val released = CountDownLatch(1)
    val continuation = AtomicReference<Continuation<Unit>>()
    val before = RestoreGate(restoring, released)
    val after = RestoreGate(restoring, released)
    // Place a gate on either side of the auto-installed Datadog element. Kotlin 1.3 restores
    // elements forwards and 1.6 restores backwards, so either version delays Datadog restoration.
    val context = CoroutineScope(dispatcher).newCoroutineContext(before) + after
    val resumer = Thread {
      check(restoring.await(10, SECONDS))
      continuation.get()?.let {
        if (cancelled) it.context[Job]?.cancel()
        it.resume(Unit)
      }
    }
    resumer.start()
    val parent = get().buildSpan("kotlin_coroutine", "parent").start()
    val parentScope = get().activateManualSpan(parent)
    try {
      try {
        runBlocking(context) {
          val suspendWithManualScope: suspend () -> Unit = {
            parent.finish()
            val manual = get().buildSpan("kotlin_coroutine", "manual").start()
            val scope = get().activateManualSpan(manual)
            try {
              when (kind) {
                "cancellable" -> suspendCancellableCoroutine<Unit> { continuation.set(it) }
                "safe" -> suspendCoroutine<Unit> { continuation.set(it) }
                "yield" -> yield()
                "delay" -> delay(20)
                "select" -> select<Unit> { onTimeout(20) { Unit } }
                else -> error("Unknown suspension API: $kind")
              }
              check(activeSpan() === manual) { "Manual scope was lost across $kind" }
              get().buildSpan("kotlin_coroutine", "child").start().finish()
            } finally {
              try {
                try {
                  check(activeSpan() === manual) { "Cleanup lost the manual scope across $kind" }
                } finally {
                  try {
                    scope.close()
                  } finally {
                    manual.finish()
                  }
                }
                resumer.join(10000)
                check(!resumer.isAlive)
                beforeCompletion.run()
              } finally {
                released.countDown()
              }
            }
          }
          if (nested) {
            withContext(CoroutineName("nested")) { suspendWithManualScope() }
          } else {
            suspendWithManualScope()
          }
        }
        check(!cancelled)
      } catch (expected: CancellationException) {
        check(cancelled)
      }
      check(before.updates.get() >= 2) { "The test did not suspend" }
      check(after.updates.get() >= 2) { "The test did not suspend" }
    } finally {
      released.countDown()
      restoring.countDown()
      resumer.join(10000)
      parentScope.close()
      dispatcher.close()
      check(workers.awaitTermination(10, SECONDS))
    }
  }

  private class Touch(val scope: ContextScope, val below: Context, val token: Any)

  /** Attaches a Datadog scope in update and closes it in restore, checking it was on top. */
  private class ScopeTouchingElement(
    private val name: String,
    private val record: (String) -> Unit
  ) : AbstractCoroutineContextElement(object : CoroutineContext.Key<ScopeTouchingElement> {}),
    ThreadContextElement<Touch> {
    val below = ThreadLocal<Context>()

    override fun updateThreadContext(context: CoroutineContext): Touch {
      val current = Context.current()
      below.set(current)
      val token = Any()
      return Touch(current.with(TOUCH_KEY, token).attach(), current, token)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Touch) {
      if (Context.current().get(TOUCH_KEY) !== oldState.token) {
        record("$name scope not on top at restore")
      }
      oldState.scope.close()
      if (Context.current() !== oldState.below) {
        record("$name restore did not return to previous context")
      }
    }
  }

  private class SuspendGate(
    private val suspended: CountDownLatch
  ) : AbstractCoroutineContextElement(SuspendGate),
    ThreadContextElement<Unit> {
    companion object Key : CoroutineContext.Key<SuspendGate>

    override fun updateThreadContext(context: CoroutineContext) = Unit

    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
      suspended.countDown()
    }
  }

  private class DestinationRestoreGate(
    private val destination: CoroutineDispatcher,
    private val restoring: CountDownLatch,
    private val released: CountDownLatch
  ) : AbstractCoroutineContextElement(object : CoroutineContext.Key<DestinationRestoreGate> {}),
    ThreadContextElement<Boolean> {
    override fun updateThreadContext(context: CoroutineContext): Boolean = context[ContinuationInterceptor] === destination

    override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) {
      if (oldState) {
        restoring.countDown()
        check(released.await(10, SECONDS)) { "Caller did not finish before destination restoration" }
      }
    }
  }

  private class RestoreGate(
    private val restoring: CountDownLatch,
    private val released: CountDownLatch
  ) : AbstractCoroutineContextElement(object : CoroutineContext.Key<RestoreGate> {}),
    ThreadContextElement<Boolean> {
    val updates = AtomicInteger()
    override fun updateThreadContext(context: CoroutineContext): Boolean = updates.incrementAndGet() == 1
    override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) {
      if (oldState) {
        restoring.countDown()
        check(released.await(10, SECONDS)) { "The new worker did not finish before the old restoration" }
      }
    }
  }
}
