package datadog.trace.bootstrap.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.jms.SessionState.MAX_CAPTURED_SPANS;
import static java.util.Collections.synchronizedList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SessionStateTest {

  @Test
  void commitTransaction() {
    SessionState sessionState = new SessionState(0, true);
    AgentSpan span1 = mock(AgentSpan.class);
    AgentSpan span2 = mock(AgentSpan.class);

    // when
    sessionState.finishOnCommit(span1);
    sessionState.finishOnCommit(span2);

    // then
    verify(span1, never()).finish();
    verify(span2, never()).finish();
    assertEquals(2, sessionState.getCapturedSpanCount());

    // when: transaction committed
    sessionState.onCommitOrRollback();

    // then: spans finished and queues empty
    verify(span1).finish();
    verify(span2).finish();
    assertEquals(0, sessionState.getCapturedSpanCount());
  }

  @Test
  void whenBufferOverflowsSpansAreFinishedEagerly() {
    SessionState sessionState = new SessionState(0, false);
    AgentSpan span1 = mock(AgentSpan.class);
    AgentSpan span2 = mock(AgentSpan.class);

    // when: fill the buffer
    for (int i = 0; i < MAX_CAPTURED_SPANS; ++i) {
      sessionState.finishOnCommit(span1);
    }

    // then: spans are not finished on entry
    verify(span1, never()).finish();
    assertEquals(MAX_CAPTURED_SPANS, sessionState.getCapturedSpanCount());

    // when: buffer overflows
    sessionState.finishOnCommit(span2);

    // then: span is finished on entry
    verify(span2).finish();
    assertEquals(MAX_CAPTURED_SPANS, sessionState.getCapturedSpanCount());

    // when: commit and add span
    sessionState.onCommitOrRollback();
    sessionState.finishOnCommit(span2);

    // then: span is enqueued and not finished (still only the single finish from the overflow)
    verify(span2).finish();
    assertEquals(1, sessionState.getCapturedSpanCount());
  }

  @Test
  void staleTimeInQueueSpansAreEvictedFromSession() throws InterruptedException {
    int workerCount = 100;
    CountDownLatch started = new CountDownLatch(workerCount);
    CountDownLatch stopped = new CountDownLatch(1);
    SessionState sessionState = new SessionState(0, true);
    List<Long> finishingTimes = synchronizedList(new ArrayList<>());

    // when: add time-in-queue spans without triggering eviction
    List<Thread> workers = new ArrayList<>();
    for (int index = 1; index <= workerCount; index++) {
      AgentSpan span = mock(AgentSpan.class);
      long startMillis = index * 1000L;
      doAnswer(
              invocation -> {
                finishingTimes.add(startMillis);
                return null;
              })
          .when(span)
          .finish();
      when(span.getStartTime()).thenReturn(startMillis);
      Thread worker =
          new Thread(
              () -> {
                sessionState.setTimeInQueueSpan(0, span);
                started.countDown();
                try {
                  stopped.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      worker.start();
      workers.add(worker);
    }
    started.await();

    // then: nothing has been finished yet
    assertEquals(workerCount, sessionState.getTimeInQueueSpanCount());
    assertTrue(finishingTimes.isEmpty());

    // when: trigger eviction of oldest time-in-queue spans
    setTimeInQueueSpanFromNewThread(sessionState);

    // then: ten oldest time-in-queue spans should have been finished
    assertEquals(91, sessionState.getTimeInQueueSpanCount());
    assertEquals(startTimes(10), new HashSet<>(finishingTimes));

    // when: trigger eviction of stopped workers
    stopped.countDown();
    for (Thread worker : workers) {
      worker.join();
    }
    for (int i = 0; i < 10; i++) {
      setTimeInQueueSpanFromNewThread(sessionState);
    }

    // then: all worker time-in-queue spans should have been finished
    assertEquals(1, sessionState.getTimeInQueueSpanCount());
    assertEquals(startTimes(workerCount), new HashSet<>(finishingTimes));
  }

  private static void setTimeInQueueSpanFromNewThread(SessionState sessionState)
      throws InterruptedException {
    Thread thread = new Thread(() -> sessionState.setTimeInQueueSpan(0, mock(AgentSpan.class)));
    thread.start();
    thread.join();
  }

  private static Set<Long> startTimes(int count) {
    Set<Long> startTimes = new HashSet<>();
    for (int index = 1; index <= count; index++) {
      startTimes.add(index * 1000L);
    }
    return startTimes;
  }
}
