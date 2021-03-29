package datadog.trace.common.writer;

import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** List writer used by tests mostly */
public class ListWriter extends CopyOnWriteArrayList<List<DDSpan>> implements Writer {

  private static final Logger log = LoggerFactory.getLogger(ListWriter.class);
  private final List<CountDownLatch> latches = new ArrayList<>();
  private final AtomicInteger traceCount = new AtomicInteger();
  private final TraceStructureWriter structureWriter = new TraceStructureWriter(true);

  public List<DDSpan> firstTrace() {
    return get(0);
  }

  @Override
  public void write(List<DDSpan> trace) {
    traceCount.incrementAndGet();
    synchronized (latches) {
      add(trace);
      for (final CountDownLatch latch : latches) {
        if (size() >= latch.getCount()) {
          while (latch.getCount() > 0) {
            latch.countDown();
          }
        }
      }
    }
    structureWriter.write(trace);
  }

  public boolean waitForTracesMax(final int number, int seconds)
      throws InterruptedException, TimeoutException {
    final CountDownLatch latch = new CountDownLatch(number);
    synchronized (latches) {
      if (size() >= number) {
        return true;
      }
      latches.add(latch);
    }
    return latch.await(seconds, TimeUnit.SECONDS);
  }

  public void waitForTraces(final int number) throws InterruptedException, TimeoutException {
    if (!waitForTracesMax(number, 20)) {
      String msg = "Timeout waiting for " + number + " trace(s). ListWriter.size() == " + size();
      log.warn(msg);
      throw new TimeoutException(msg);
    }
  }

  public void waitUntilReported(final DDSpan span) throws InterruptedException, TimeoutException {
    while (true) {
      final CountDownLatch latch = new CountDownLatch(size() + 1);
      synchronized (latches) {
        latches.add(latch);
      }
      if (isReported(span)) {
        return;
      }
      if (!latch.await(20, TimeUnit.SECONDS)) {
        String msg = "Timeout waiting for span to be reported: " + span;
        log.warn(msg);
        throw new TimeoutException(msg);
      }
    }
  }

  private boolean isReported(DDSpan span) {
    for (List<DDSpan> trace : this) {
      for (DDSpan aSpan : trace) {
        if (aSpan == span) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void incrementDropCounts(int spanCount) {}

  @Override
  public void start() {
    close();
  }

  @Override
  public boolean flush() {
    return true;
  }

  @Override
  public void close() {
    clear();
    synchronized (latches) {
      for (final CountDownLatch latch : latches) {
        while (latch.getCount() > 0) {
          latch.countDown();
        }
      }
      latches.clear();
    }
  }

  @Override
  public String toString() {
    return "ListWriter { size=" + size() + " }";
  }
}
