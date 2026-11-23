package datadog.trace.instrumentation.jetty12;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tabletest.junit.TableTest;

/** Unit tests for {@link JettyDeferredCompletion}. */
class JettyDeferredCompletionTest {

  private static final IOException FAILURE = new IOException("blocked");

  private static final long JOIN_TIMEOUT_MS = SECONDS.toMillis(10);

  private static final int RACE_ITERATIONS = 2_000;

  private final RecordingCallback delegate = new RecordingCallback();
  private final JettyDeferredCompletion completion = new JettyDeferredCompletion(delegate);

  @TableTest({
    "Scenario | Succeed | Expected ",
    "success  | true    | succeeded",
    "failure  | false   | failed   "
  })
  void forwardsImmediatelyWhenNoWriteIsInFlight(boolean succeed, String expected) {
    complete(completion, succeed);

    assertEquals(List.of(expected), delegate.calls);
  }

  @TableTest({
    "Scenario | Succeed | Expected ",
    "success  | true    | succeeded",
    "failure  | false   | failed   "
  })
  void parksWhileWritingAndReplaysTheSameOutcome(boolean succeed, String expected) {
    completion.blockWriteStarted();
    complete(completion, succeed);

    assertTrue(delegate.calls.isEmpty());

    completion.blockWriteFinished();

    assertEquals(List.of(expected), delegate.calls);
    if (!succeed) {
      assertSame(FAILURE, delegate.failure);
    }
  }

  @Test
  void replaysNullFailureAsFailure() {
    completion.blockWriteStarted();
    completion.failed(null);
    completion.blockWriteFinished();

    assertEquals(List.of("failed"), delegate.calls);
  }

  @Test
  void forwardsDirectlyWhenTheWriteFinishedFirst() {
    completion.blockWriteStarted();
    completion.blockWriteFinished();

    assertTrue(delegate.calls.isEmpty());

    completion.failed(FAILURE);

    assertEquals(List.of("failed"), delegate.calls);
    assertSame(FAILURE, delegate.failure);
  }

  @Test
  void dropsSecondCompletionWhileParked() {
    completion.blockWriteStarted();
    completion.failed(FAILURE);
    completion.succeeded();
    completion.failed(new IOException("other"));
    completion.blockWriteFinished();

    assertEquals(List.of("failed"), delegate.calls);
    assertSame(FAILURE, delegate.failure);
  }

  @Test
  void dropsSecondCompletionAfterForwarding() {
    completion.succeeded();
    completion.failed(FAILURE);
    completion.succeeded();

    assertEquals(List.of("succeeded"), delegate.calls);
  }

  @Test
  void replaysExactlyOnce() {
    completion.blockWriteStarted();
    completion.succeeded();
    completion.blockWriteFinished();
    completion.blockWriteFinished();
    completion.blockWriteStarted();
    completion.blockWriteFinished();

    assertEquals(List.of("succeeded"), delegate.calls);
  }

  @Test
  void finishedWithoutStartIsANoOp() {
    completion.blockWriteFinished();
    completion.succeeded();

    assertEquals(List.of("succeeded"), delegate.calls);
  }

  @Test
  void startedAfterCompletionIsANoOp() {
    completion.succeeded();
    completion.blockWriteStarted();
    completion.failed(FAILURE);
    completion.blockWriteFinished();

    assertEquals(List.of("succeeded"), delegate.calls);
  }

  @Test
  void retriedWriteParksUntilTheRetryFinishes() {
    completion.blockWriteStarted();
    completion.blockWriteFinished();
    completion.blockWriteStarted();
    completion.failed(FAILURE);

    assertTrue(delegate.calls.isEmpty());

    completion.blockWriteFinished();

    assertEquals(List.of("failed"), delegate.calls);
  }

  @Test
  void delegateThrowingDuringReplayDoesNotEscape() {
    Callback throwing =
        new Callback() {
          @Override
          public void failed(Throwable x) {
            throw new IllegalStateException("write pending");
          }
        };
    JettyDeferredCompletion wrapper = new JettyDeferredCompletion(throwing);
    wrapper.blockWriteStarted();
    wrapper.failed(FAILURE);

    wrapper.blockWriteFinished();
  }

  @ParameterizedTest
  @EnumSource(InvocationType.class)
  void delegatesInvocationType(InvocationType type) {
    RecordingCallback typed = new RecordingCallback(type);

    assertEquals(type, new JettyDeferredCompletion(typed).getInvocationType());
  }

  @Test
  void raceBetweenCompletionAndWriteFinishedCompletesExactlyOnce() throws Exception {
    for (int i = 0; i < RACE_ITERATIONS; i++) {
      boolean succeed = (i & 1) == 0;
      RecordingCallback recording = new RecordingCallback();
      JettyDeferredCompletion wrapper = new JettyDeferredCompletion(recording);
      wrapper.blockWriteStarted();
      CountDownLatch start = new CountDownLatch(1);
      Thread completer = new Thread(() -> awaitThen(start, () -> complete(wrapper, succeed)));
      Thread writer = new Thread(() -> awaitThen(start, wrapper::blockWriteFinished));
      completer.start();
      writer.start();
      start.countDown();
      completer.join(JOIN_TIMEOUT_MS);
      writer.join(JOIN_TIMEOUT_MS);

      assertEquals(List.of(succeed ? "succeeded" : "failed"), recording.calls, "iteration " + i);
      if (!succeed) {
        assertSame(FAILURE, recording.failure, "iteration " + i);
      }
    }
  }

  @Test
  void raceBetweenTwoCompletionsWhileWritingCompletesExactlyOnce() throws Exception {
    for (int i = 0; i < RACE_ITERATIONS; i++) {
      RecordingCallback recording = new RecordingCallback();
      JettyDeferredCompletion wrapper = new JettyDeferredCompletion(recording);
      wrapper.blockWriteStarted();
      CountDownLatch start = new CountDownLatch(1);
      Thread failer = new Thread(() -> awaitThen(start, () -> wrapper.failed(FAILURE)));
      Thread succeeder = new Thread(() -> awaitThen(start, wrapper::succeeded));
      failer.start();
      succeeder.start();
      start.countDown();
      failer.join(JOIN_TIMEOUT_MS);
      succeeder.join(JOIN_TIMEOUT_MS);
      wrapper.blockWriteFinished();

      assertEquals(1, recording.calls.size(), "iteration " + i);
      if (recording.calls.get(0).equals("failed")) {
        assertSame(FAILURE, recording.failure, "iteration " + i);
      }
    }
  }

  private static void complete(JettyDeferredCompletion wrapper, boolean succeed) {
    if (succeed) {
      wrapper.succeeded();
    } else {
      wrapper.failed(FAILURE);
    }
  }

  private static void awaitThen(CountDownLatch start, Runnable action) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    action.run();
  }

  private static final class RecordingCallback implements Callback {
    final List<String> calls = new CopyOnWriteArrayList<>();
    final InvocationType type;
    volatile Throwable failure;

    RecordingCallback() {
      this(NON_BLOCKING);
    }

    RecordingCallback(InvocationType type) {
      this.type = type;
    }

    @Override
    public void succeeded() {
      calls.add("succeeded");
    }

    @Override
    public void failed(Throwable x) {
      failure = x;
      calls.add("failed");
    }

    @Override
    public InvocationType getInvocationType() {
      return type;
    }
  }
}
