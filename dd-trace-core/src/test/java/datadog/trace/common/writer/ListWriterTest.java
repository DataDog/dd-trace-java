package datadog.trace.common.writer;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class ListWriterTest extends DDCoreJavaSpecification {

  @Test
  void clearDuringPublicationDoesNotLeaveAStaleTraceCount() throws Exception {
    CountDownLatch added = new CountDownLatch(1);
    CountDownLatch finishAdding = new CountDownLatch(1);
    ListWriter writer =
        new ListWriter() {
          @Override
          public boolean add(List<DDSpan> trace) {
            boolean result = super.add(trace);
            added.countDown();
            awaitLatch(finishAdding);
            return result;
          }
        };
    FutureTask<Void> publication = new FutureTask<>(() -> writer.write(emptyList()), null);
    FutureTask<Void> clearing = new FutureTask<>(writer::clear, null);
    Thread publisher = new Thread(publication, "trace-publisher");
    Thread clearer = new Thread(clearing, "trace-clearer");
    try {
      publisher.start();
      assertTrue(added.await(10, SECONDS));
      clearer.start();
      // The original writer lets clear finish between adding the trace and counting it.
      awaitBlockedOrFinished(clearer, clearing);
      finishAdding.countDown();
      publication.get(10, SECONDS);
      clearing.get(10, SECONDS);

      assertTrue(writer.isEmpty());
      assertEquals(0, writer.getTraceCount());
      assertFalse(writer.waitForTracesMax(1, 0));

      writer.write(emptyList());
      assertTrue(writer.waitForTracesMax(1, 0));
      assertEquals(1, writer.size());
      assertEquals(1, writer.getTraceCount());
    } finally {
      finishAdding.countDown();
      publisher.interrupt();
      clearer.interrupt();
      publisher.join(10000);
      clearer.join(10000);
    }
  }

  @Test
  void publicationBetweenPredicateCheckAndWaitDoesNotLoseNotification() throws Exception {
    CountDownLatch checking = new CountDownLatch(1);
    CountDownLatch finishChecking = new CountDownLatch(1);
    ListWriter writer =
        new ListWriter() {
          @Override
          public Iterator<List<DDSpan>> iterator() {
            Iterator<List<DDSpan>> snapshot = super.iterator();
            checking.countDown();
            awaitLatch(finishChecking);
            return snapshot;
          }
        };
    DDSpan span = buildSpan(0, "test", emptyMap());
    FutureTask<Void> reporting =
        new FutureTask<>(() -> {
          writer.waitUntilReported(span, 30, SECONDS);
          return null;
        });
    FutureTask<Void> publication = new FutureTask<>(() -> writer.write(singletonList(span)), null);
    Thread waiter = new Thread(reporting, "trace-waiter");
    Thread publisher = new Thread(publication, "trace-publisher");
    try {
      waiter.start();
      assertTrue(checking.await(10, SECONDS));
      publisher.start();
      // The original writer publishes and notifies while the waiter still sees an empty snapshot.
      awaitBlockedOrFinished(publisher, publication);
      finishChecking.countDown();
      publication.get(10, SECONDS);
      // A lost notification otherwise leaves the waiter asleep until its 30-second deadline.
      reporting.get(10, SECONDS);
    } finally {
      finishChecking.countDown();
      waiter.interrupt();
      publisher.interrupt();
      waiter.join(10000);
      publisher.join(10000);
    }
  }

  @Test
  void traceCountIncludesTracesRemovedFromTheListUntilCleared() {
    ListWriter writer = new ListWriter();
    writer.write(emptyList());
    writer.remove(0);

    assertTrue(writer.isEmpty());
    assertEquals(1, writer.getTraceCount());

    writer.clear();
    assertEquals(0, writer.getTraceCount());
  }

  private static void awaitBlockedOrFinished(Thread thread, Future<?> task) {
    long deadline = System.nanoTime() + SECONDS.toNanos(10);
    while (!task.isDone() && thread.getState() != Thread.State.BLOCKED) {
      assertTrue(System.nanoTime() < deadline, "Worker did not block or finish");
      Thread.yield();
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
