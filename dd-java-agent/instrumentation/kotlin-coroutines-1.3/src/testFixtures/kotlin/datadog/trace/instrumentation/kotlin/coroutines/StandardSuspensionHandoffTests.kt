package datadog.trace.instrumentation.kotlin.coroutines

import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.newCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
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
  fun run(kind: String, cancelled: Boolean, beforeCompletion: Runnable) {
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
