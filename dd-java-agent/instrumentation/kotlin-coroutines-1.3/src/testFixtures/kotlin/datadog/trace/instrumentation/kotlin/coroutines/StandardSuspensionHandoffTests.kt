package datadog.trace.instrumentation.kotlin.coroutines

import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class StandardSuspensionHandoffTests {
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
