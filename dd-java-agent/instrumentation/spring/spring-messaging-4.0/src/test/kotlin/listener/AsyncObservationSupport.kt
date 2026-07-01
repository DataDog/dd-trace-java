package listener

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class AsyncObservationSupport {
  companion object {
    // Match the listener completion waits in these tests so the gate does not fail earlier than
    // the enclosing async test would.
    private const val TIMEOUT_SECONDS = 15L
  }

  @Volatile private var asyncStarted = CountDownLatch(0)

  @Volatile private var allowAsyncCompletion = CountDownLatch(0)

  @Volatile
  var activeParentFinished: Boolean? = null
    private set

  fun prepareAsyncObservation() {
    asyncStarted = CountDownLatch(1)
    allowAsyncCompletion = CountDownLatch(1)
    activeParentFinished = null
  }

  @Throws(InterruptedException::class)
  fun awaitAsyncStarted() {
    if (!asyncStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw AssertionError("timed out waiting for async listener to start")
    }
  }

  fun releaseAsyncObservation() {
    allowAsyncCompletion.countDown()
  }

  protected fun markAsyncStarted() {
    asyncStarted.countDown()
  }

  protected fun recordActiveParentFinished(activeParentFinished: Boolean) {
    this.activeParentFinished = activeParentFinished
  }

  @Throws(InterruptedException::class)
  protected fun awaitAsyncRelease() {
    if (!allowAsyncCompletion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw AssertionError("timed out waiting for test to release async listener")
    }
  }
}
