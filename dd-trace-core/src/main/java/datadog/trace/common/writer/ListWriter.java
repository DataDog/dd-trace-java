package datadog.trace.common.writer;

import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** List writer used by tests mostly */
public class ListWriter extends CopyOnWriteArrayList<List<DDSpan>> implements Writer {
  private final TraceProcessor processor = new TraceProcessor();
  private final List<CountDownLatch> latches = new ArrayList<>();
  private final AtomicInteger traceCount = new AtomicInteger();
  private final TraceStructureWriter structureWriter = new TraceStructureWriter();

  public List<DDSpan> firstTrace() {
    return get(0);
  }

  @Override
  public void write(List<DDSpan> trace) {
    incrementTraceCount();
    synchronized (latches) {
      trace = processor.onTraceComplete(trace);
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

  public void waitForTraces(final int number) throws InterruptedException, TimeoutException {
    final CountDownLatch latch = new CountDownLatch(number);
    synchronized (latches) {
      if (size() >= number) {
        return;
      }
      latches.add(latch);
    }
    if (!latch.await(20, TimeUnit.SECONDS)) {
      throw new TimeoutException(
          "Timeout waiting for " + number + " trace(s). ListWriter.size() == " + size());
    }
  }

  @Override
  public void incrementTraceCount() {
    traceCount.incrementAndGet();
  }

  @Override
  public void start() {
    close();
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
