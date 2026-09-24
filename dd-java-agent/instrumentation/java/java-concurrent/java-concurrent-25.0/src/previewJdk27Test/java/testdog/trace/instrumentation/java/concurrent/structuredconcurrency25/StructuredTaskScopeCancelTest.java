package testdog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * JDK specific tests for the structured-task-scope continuation cleanup, isolated from {@code
 * StructuredTaskScope25Test} because it uses the Java 27 {@code StructuredTaskScope.Joiner} API.
 */
@SuppressWarnings("preview")
public class StructuredTaskScopeCancelTest extends AbstractInstrumentationTest {
  @Test
  void testForkIntoCancelledScopeDoesNotLeakContinuation() throws Exception {
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open(new CancelOnForkJoiner<>())) {
        scope.fork(this::task);
        scope.join();
      }
    }
    span.finish();

    assertTraces(trace(span().root().operationName("parent")));
  }

  /**
   * A subtask is created before {@code fork()} can fail (here in {@code Joiner.onFork}), so its
   * continuation is captured but its thread never starts. It must still be released at scope close.
   */
  @Test
  void testFailedForkDoesNotLeakContinuation() {
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open(new FailOnForkJoiner<>())) {
        assertThrows(IllegalStateException.class, () -> scope.fork(this::task));
      }
    }
    span.finish();

    assertTraces(trace(span().root().operationName("parent")));
  }

  /**
   * A subtask whose thread actually starts must keep the parent context even when the scope is
   * canceled (here by forking a second subtask). The continuation cleanup happens at scope close —
   * not at fork — so a started subtask has already consumed its continuation and is never stripped
   * of its context, while the never-started sibling's continuation is released at close.
   */
  @Test
  void testStartedSubtaskKeepsContextWhenSiblingCancelsScope() throws Exception {
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open(new CancelOnSecondForkJoiner<>())) {
        var firstStarted = new CountDownLatch(1);
        // The first subtask starts and produces its span before the scope is canceled.
        scope.fork(
            () -> {
              Void result = task();
              firstStarted.countDown();
              return result;
            });
        firstStarted.await();
        // Forking the second subtask cancels the scope, so its thread never starts.
        scope.fork(this::task);
        scope.join();
      }
    }
    span.finish();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("child")));
  }

  /**
   * A {@code close()} call from a non-owner thread fails before joining the subtasks. It must not
   * release the continuation of a subtask that was forked but has not run yet.
   */
  @Test
  void testNonOwnerCloseKeepsPendingSubtaskContext() throws Exception {
    var subtaskGate = new CountDownLatch(1);
    // Hold the subtask before its run() until the non-owner close attempt is done
    ThreadFactory gatedFactory =
        subtask ->
            new Thread(
                () -> {
                  try {
                    subtaskGate.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  subtask.run();
                });
    var span = tracer.startSpan("test", "parent");
    try (var ignored = tracer.activateSpan(span)) {
      try (var scope = StructuredTaskScope.open(cf -> cf.withThreadFactory(gatedFactory))) {
        scope.fork(this::task);
        var closeFailure = new AtomicReference<Throwable>();
        var nonOwner =
            new Thread(
                () -> {
                  try {
                    scope.close();
                  } catch (Throwable t) {
                    closeFailure.set(t);
                  }
                });
        nonOwner.start();
        nonOwner.join();
        assertInstanceOf(WrongThreadException.class, closeFailure.get());
        subtaskGate.countDown();
        scope.join();
      }
    }
    span.finish();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("child")));
  }

  Void task() {
    tracer.startSpan("test", "child").finish();
    return null;
  }

  /** Cancels the scope as soon as the first subtask is forked. */
  static final class CancelOnForkJoiner<T>
      implements StructuredTaskScope.Joiner<T, Void, RuntimeException> {
    @Override
    public boolean onFork(StructuredTaskScope.Subtask<T> subtask) {
      return true; // Cancel the scope as soon as the first subtask is forked.
    }

    @Override
    public Void result() {
      return null;
    }

    @Override
    public Void timeout() {
      return null; // No timeout configured
    }
  }

  /** Lets the first subtask start, then cancels the scope when a second subtask is forked. */
  static final class CancelOnSecondForkJoiner<T>
      implements StructuredTaskScope.Joiner<T, Void, RuntimeException> {
    private int forks = 0; // onFork is only called on the scope owner thread

    @Override
    public boolean onFork(StructuredTaskScope.Subtask<T> subtask) {
      return ++forks >= 2; // cancel the scope when the second subtask is forked
    }

    @Override
    public Void result() {
      return null;
    }

    @Override
    public Void timeout() {
      return null; // No timeout configured
    }
  }

  /** Fails every fork after the subtask is created. */
  static final class FailOnForkJoiner<T>
      implements StructuredTaskScope.Joiner<T, Void, RuntimeException> {
    @Override
    public boolean onFork(StructuredTaskScope.Subtask<T> subtask) {
      throw new IllegalStateException("fork rejected");
    }

    @Override
    public Void result() {
      return null;
    }

    @Override
    public Void timeout() {
      return null; // No timeout configured
    }
  }
}
