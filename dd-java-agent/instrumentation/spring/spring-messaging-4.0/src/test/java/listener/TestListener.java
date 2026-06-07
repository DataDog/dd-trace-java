package listener;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Test SQS listener. The async coordination latches mirror the Kotlin {@code
 * AsyncObservationSupport} used by the Kafka coroutine test, reimplemented here in Java so this
 * fixture stays on the Java source set (a Java test cannot reference Groovy/Kotlin test sources
 * under the module's compile wiring).
 */
@Component
public class TestListener {
  private static final long TIMEOUT_SECONDS = 15L;

  private volatile CountDownLatch asyncStarted = new CountDownLatch(0);
  private volatile CountDownLatch allowAsyncCompletion = new CountDownLatch(0);
  private volatile Boolean activeParentFinished = null;

  public void prepareAsyncObservation() {
    asyncStarted = new CountDownLatch(1);
    allowAsyncCompletion = new CountDownLatch(1);
    activeParentFinished = null;
  }

  public void awaitAsyncStarted() throws InterruptedException {
    if (!asyncStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError("timed out waiting for async listener to start");
    }
  }

  public void releaseAsyncObservation() {
    allowAsyncCompletion.countDown();
  }

  public Boolean getActiveParentFinished() {
    return activeParentFinished;
  }

  @SqsListener(queueNames = "SpringListenerSQS")
  public void observe(String message) {
    System.out.println("Received " + message);
  }

  @SqsListener(queueNames = "SpringListenerSQSAsync")
  public CompletableFuture<Void> observeAsync(String message) {
    return CompletableFuture.runAsync(
        () -> {
          activeParentFinished = ((DDSpan) activeSpan()).isFinished();
          // Keep the test gate from blocking before the test observes the active span.
          markAsyncStarted();
          awaitAsyncRelease();
          // Create a child span inside the CompletableFuture body. It should be linked to
          // spring.consume, proving that span was active across the async execution.
          AgentSpan childSpan = startSpan("test", "async.child");
          AgentScope childScope = activateSpan(childSpan);
          childScope.close();
          childSpan.finish();
          System.out.println("Async received " + message);
        });
  }

  private void markAsyncStarted() {
    asyncStarted.countDown();
  }

  private void awaitAsyncRelease() {
    try {
      if (!allowAsyncCompletion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for test to release async listener");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
