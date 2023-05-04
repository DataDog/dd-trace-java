import datadog.trace.agent.test.AgentTestRunner
import dev.failsafe.Failsafe
import dev.failsafe.RetryPolicy
import dev.failsafe.function.CheckedSupplier

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class FailsafeTest extends AgentTestRunner {

  def "should propagate across async executions"() {
    setup:
    def retryCounter = new AtomicInteger()
    def successCounter = new AtomicInteger()
    def retryPolicy = RetryPolicy.builder()
      .onRetry { retryCounter.incrementAndGet() }
      .onSuccess { successCounter.incrementAndGet() }
      .withMaxAttempts(2)
      .build()

    when:
    runUnderTrace("parent", {
      Failsafe.with(retryPolicy)
        .getStageAsync(new CheckedSupplier<CompletableFuture<Boolean>>() {
          @Override
          CompletableFuture<Boolean> get() throws Throwable {
            boolean result = activeSpan() != null && activeSpan().operationName == "parent"
            if (retryCounter.get() == 0) {
              throw new RuntimeException("trigger retry")
            }
            return CompletableFuture.supplyAsync { result }
          }
        })
        .handle { result, error ->
          assert result
          assert activeSpan().operationName == "parent"
        }.get()
    })

    then:
    successCounter.get() == 1
    retryCounter.get() == 1
  }
}
