import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling
import datadog.trace.core.DDSpan
import spock.lang.Requires
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Requires({
  JavaVirtualMachine.isJavaVersionAtLeast(19)
})
class VirtualThreadProfilingContextForkedTest extends InstrumentationSpecification {

  @Shared
  def virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()

  @Override
  protected void configurePreAgent() {
    // Enable profiling and profiling context integration
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.profiling.queueing.time.enabled", "true")
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling()
    super.configurePreAgent()
  }

  def setup() {
    // Reset profiling context integration before each test
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.clear()
  }

  def cleanup() {
    // Ensure clean state after each test
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.clear()
  }

  def cleanupSpec() {
    virtualExecutor?.shutdown()
    virtualExecutor?.awaitTermination(5, TimeUnit.SECONDS)
  }

  def "test virtual thread mount unmount context lifecycle"() {
    setup:
    def latch = new CountDownLatch(1)
    def completedLatch = new CountDownLatch(1)
    def exception = new AtomicReference<Exception>()

    when:
    runUnderTrace("parent") {
      virtualExecutor.submit({
        try {
          // This blocking operation should force virtual thread to mount/unmount
          assert latch.await(5, TimeUnit.SECONDS)
          // Additional blocking to ensure multiple mount/unmount cycles
          Thread.sleep(10)
          completedLatch.countDown()
        } catch (Exception e) {
          exception.set(e)
          completedLatch.countDown()
        }
      } as Runnable)

      // Allow virtual thread to start and block
      Thread.sleep(50)
      latch.countDown()
    }

    // Wait for virtual thread completion
    assert completedLatch.await(10, TimeUnit.SECONDS)
    if (exception.get() != null) {
      throw exception.get()
    }

    // Give time for profiling context operations to complete
    Thread.sleep(100)

    then:
    // Verify that profiling context integration was called
    with(TEST_PROFILING_CONTEXT_INTEGRATION) {
      attachments.get() > 0
      detachments.get() > 0
      // Context should be balanced (all opened contexts were closed)
      isBalanced()
      // Should have at least one attach/detach pair per virtual thread
      attachments.get() >= 1
      detachments.get() >= 1
    }
  }

  def "test concurrent virtual threads context isolation"() {
    setup:
    def numThreads = 3
    def startLatch = new CountDownLatch(numThreads)
    def proceedLatch = new CountDownLatch(1)
    def completionLatch = new CountDownLatch(numThreads)
    def exceptions = new AtomicReference<List<Exception>>([])
    def spanIds = Collections.synchronizedSet(new HashSet<Long>())

    when:
    (1..numThreads).each { threadId ->
      runUnderTrace("parent-${threadId}") {
        virtualExecutor.submit({
          try {
            // Capture span ID for validation
            def currentSpan = TEST_TRACER.activeSpan()
            if (currentSpan) {
              spanIds.add(currentSpan.spanId)
            }

            startLatch.countDown()
            // Wait for all threads to start
            assert proceedLatch.await(5, TimeUnit.SECONDS)

            // Blocking operations to force mount/unmount
            Thread.sleep(50 + (threadId * 10))

            completionLatch.countDown()
          } catch (Exception e) {
            synchronized(exceptions) {
              exceptions.get().add(e)
            }
            completionLatch.countDown()
          }
        } as Runnable)
      }
    }

    // Wait for all threads to start, then let them proceed
    assert startLatch.await(10, TimeUnit.SECONDS)
    proceedLatch.countDown()

    // Wait for completion
    assert completionLatch.await(15, TimeUnit.SECONDS)

    if (!exceptions.get().isEmpty()) {
      throw new Exception("Thread execution failed: ${exceptions.get()}")
    }

    // Give time for profiling context operations to complete
    Thread.sleep(200)

    then:
    // Verify each virtual thread had its own span context
    spanIds.size() == numThreads

    // Verify profiling context operations scaled with thread count
    with(TEST_PROFILING_CONTEXT_INTEGRATION) {
      attachments.get() >= numThreads
      detachments.get() >= numThreads
      isBalanced()
      // Should have more context operations due to multiple threads
      attachments.get() > 1
      detachments.get() > 1
    }
  }

  def "test queue timing integration with virtual threads"() {
    setup:
    def taskCompletedLatch = new CountDownLatch(1)
    def exception = new AtomicReference<Exception>()

    when:
    runUnderTrace("queue-timing-parent") {
      virtualExecutor.submit({
        try {
          // Perform some work that should be timed
          Thread.sleep(20)
          taskCompletedLatch.countDown()
        } catch (Exception e) {
          exception.set(e)
          taskCompletedLatch.countDown()
        }
      } as Runnable)
    }

    assert taskCompletedLatch.await(10, TimeUnit.SECONDS)
    if (exception.get() != null) {
      throw exception.get()
    }

    // Give time for timing operations to be recorded
    Thread.sleep(200)

    then:
    // Verify profiling context integration was called
    with(TEST_PROFILING_CONTEXT_INTEGRATION) {
      isBalanced()
      attachments.get() > 0
      detachments.get() > 0
    }

    // Verify queue timing was recorded
    !TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.isEmpty()

    // Validate timing entry details
    def timing = TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.peek()
    timing != null
    timing.task != null
    // Should show task migration between threads
    timing.origin != null
  }

  def "test nested span propagation with virtual threads"() {
    setup:
    def childSpanStartedLatch = new CountDownLatch(1)
    def parentCanCompleteLatch = new CountDownLatch(1)
    def allCompletedLatch = new CountDownLatch(1)
    def exception = new AtomicReference<Exception>()
    def capturedSpanIds = Collections.synchronizedList([])

    when:
    runUnderTrace("nested-parent") { parentSpan ->
      capturedSpanIds.add(parentSpan.spanId)

      virtualExecutor.submit({
        try {
          // Create child span within virtual thread
          runUnderTrace("nested-child") { childSpan ->
            capturedSpanIds.add(childSpan.spanId)
            // Verify parent-child relationship
            assert childSpan.parentId == parentSpan.spanId

            childSpanStartedLatch.countDown()
            // Wait for parent to be ready to complete
            assert parentCanCompleteLatch.await(5, TimeUnit.SECONDS)

            // Blocking operation to force context management
            Thread.sleep(30)
          }

          allCompletedLatch.countDown()
        } catch (Exception e) {
          exception.set(e)
          allCompletedLatch.countDown()
        }
      } as Runnable)

      // Wait for child to start
      assert childSpanStartedLatch.await(5, TimeUnit.SECONDS)
      parentCanCompleteLatch.countDown()
    }

    assert allCompletedLatch.await(10, TimeUnit.SECONDS)
    if (exception.get() != null) {
      throw exception.get()
    }

    // Wait for profiling operations to complete
    Thread.sleep(200)

    then:
    // Verify span hierarchy was maintained
    capturedSpanIds.size() == 2

    // Verify profiling context handled nested spans correctly
    with(TEST_PROFILING_CONTEXT_INTEGRATION) {
      isBalanced()
      attachments.get() > 0
      detachments.get() > 0
    }

    // Verify traces were properly recorded
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0) as List<DDSpan>
    trace.size() == 2

    def parentSpan = trace.find { it.operationName == "nested-parent" }
    def childSpan = trace.find { it.operationName == "nested-child" }

    parentSpan != null
    childSpan != null
    childSpan.parentId == parentSpan.spanId
  }

  def "test error handling and cleanup"() {
    setup:
    def taskStartedLatch = new CountDownLatch(1)
    def exceptionThrownLatch = new CountDownLatch(1)
    def expectedExceptionMessage = "Intentional test exception"

    when:
    runUnderTrace("error-parent") {
      virtualExecutor.submit({
        try {
          taskStartedLatch.countDown()
          // Blocking operation to ensure mount/unmount happens
          Thread.sleep(20)
          // Throw intentional exception
          throw new RuntimeException(expectedExceptionMessage)
        } catch (RuntimeException e) {
          // Exception is expected, signal completion
          if (e.message == expectedExceptionMessage) {
            exceptionThrownLatch.countDown()
          } else {
            throw e
          }
        }
      } as Runnable)
    }

    assert taskStartedLatch.await(5, TimeUnit.SECONDS)
    assert exceptionThrownLatch.await(10, TimeUnit.SECONDS)

    // Give time for cleanup operations
    Thread.sleep(200)

    then:
    // Even with exceptions, profiling context should be properly cleaned up
    with(TEST_PROFILING_CONTEXT_INTEGRATION) {
      isBalanced()
      // Should have profiling operations despite the exception
      attachments.get() > 0 || detachments.get() > 0
    }
  }

  def "test multiple blocking operations force multiple mount unmount cycles"() {
    setup:
    def operationsCompleted = new AtomicInteger(0)
    def completionLatch = new CountDownLatch(1)
    def exception = new AtomicReference<Exception>()
    def numOperations = 5

    when:
    runUnderTrace("multiple-blocking-parent") {
      virtualExecutor.submit({
        try {
          for (int i = 0; i < numOperations; i++) {
            // Each sleep should potentially cause mount/unmount
            Thread.sleep(10)
            operationsCompleted.incrementAndGet()

            // Add some variety in blocking operations
            if (i % 2 == 0) {
              def latch = new CountDownLatch(1)
              latch.countDown()
              latch.await(1, TimeUnit.MILLISECONDS)
            }
          }
          completionLatch.countDown()
        } catch (Exception e) {
          exception.set(e)
          completionLatch.countDown()
        }
      } as Runnable)
    }

    assert completionLatch.await(15, TimeUnit.SECONDS)
    if (exception.get() != null) {
      throw exception.get()
    }

    // Give time for all profiling operations to complete
    Thread.sleep(300)

    then:
    operationsCompleted.get() == numOperations

    // Multiple blocking operations should result in more profiling context activity
    with(TEST_PROFILING_CONTEXT_INTEGRATION) {
      isBalanced()
      attachments.get() > 0
      detachments.get() > 0
      // More operations might lead to more context switching
      attachments.get() >= 1
      detachments.get() >= 1
    }
  }
}
