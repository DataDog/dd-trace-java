package datadog.trace.common.writer;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.tagprocessor.PeerServiceCalculator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  public static final Filter ACCEPT_ALL =
      new Filter() {
        @Override
        public boolean accept(List<DDSpan> trace) {
          return true;
        }
      };

  private final List<CountDownLatch> latches = new ArrayList<>();
  private final AtomicInteger traceCount = new AtomicInteger();
  private final TraceStructureWriter structureWriter = new TraceStructureWriter(true);

  private final PeerServiceCalculator peerServiceCalculator = new PeerServiceCalculator();
  private Filter filter = ACCEPT_ALL;

  public List<DDSpan> firstTrace() {
    return get(0);
  }

  @Override
  public void write(List<DDSpan> trace) {
    if (!filter.accept(trace)) {
      return;
    }
    for (DDSpan span : trace) {
      // todo: this can be better with span.processTagsAndBaggage(NoopMetadataConsumer.INSTANCE);
      // we cannot use the full postProcessor chain since it trigger also query obsfuscator and will
      // make fail a lot of tests
      // only kicks in peer.service computation (avoid scrambling queries and url)
      final Map<String, ?> enriched =
          peerServiceCalculator.processTags(new HashMap<>(span.getTags()));
      final Object peerServiceSource = enriched.get(DDTags.PEER_SERVICE_SOURCE);
      // we needed to copy the original tag map since it's unmodifiable
      if (peerServiceSource != null) {
        span.setTag(Tags.PEER_SERVICE, enriched.get(Tags.PEER_SERVICE));
        span.setTag(DDTags.PEER_SERVICE_SOURCE, peerServiceSource);
      }
    }
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
      String msg =
          "Timeout waiting for "
              + number
              + " trace(s). ListWriter.size() == "
              + size()
              + " : "
              + super.toString();
      log.warn(msg);
      throw new TimeoutException(msg);
    }
  }

  public void waitUntilReported(final DDSpan span) throws InterruptedException, TimeoutException {
    waitUntilReported(span, 20, TimeUnit.SECONDS);
  }

  public void waitUntilReported(final DDSpan span, int timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException {
    while (true) {
      final CountDownLatch latch = new CountDownLatch(size() + 1);
      synchronized (latches) {
        latches.add(latch);
      }
      if (isReported(span)) {
        return;
      }
      if (!latch.await(timeout, unit)) {
        String msg = "Timeout waiting for span to be reported: " + span;
        log.warn(msg);
        throw new TimeoutException(msg);
      }
    }
  }

  /**
   * Set a filter to be applied to all incoming traces to determine whether they should be written
   * or not.
   */
  public void setFilter(Filter filter) {
    this.filter = filter;
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
    filter = ACCEPT_ALL;
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

  /** Interface for filtering out select traces from being written. */
  public interface Filter {
    boolean accept(List<DDSpan> trace);
  }
}
