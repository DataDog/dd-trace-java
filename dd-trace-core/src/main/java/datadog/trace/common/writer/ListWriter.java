package datadog.trace.common.writer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.core.DDSpan;
import datadog.trace.core.MetadataConsumer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** List writer used by tests mostly */
public class ListWriter extends CopyOnWriteArrayList<List<DDSpan>> implements Writer {
  public static final int WAIT_FOR_TRACES_TIMEOUT_SECONDS = 20;

  private static final Logger log = LoggerFactory.getLogger(ListWriter.class);
  private static final Filter ACCEPT_ALL = trace -> true;

  private final AtomicInteger traceCount = new AtomicInteger();
  private final TraceStructureWriter structureWriter = new TraceStructureWriter(true);
  private final Object monitor = new Object();

  private Filter filter = ACCEPT_ALL;

  private MetadataConsumer metadataConsumer = MetadataConsumer.NO_OP;

  public List<DDSpan> firstTrace() {
    return get(0);
  }

  @Override
  public void write(List<DDSpan> trace) {
    if (!filter.accept(trace)) {
      return;
    }
    for (DDSpan span : trace) {
      // This is needed to properly do all delayed processing to make this writer even
      // remotely realistic so the test actually test something
      span.processTagsAndBaggage(metadataConsumer);
    }

    add(trace);
    structureWriter.write(trace);

    traceCount.incrementAndGet();
    synchronized (monitor) {
      monitor.notifyAll();
    }
  }

  private boolean awaitUntilDeadline(long timeout, TimeUnit unit, BooleanSupplier predicate)
      throws InterruptedException {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);

    while (true) {
      if (predicate.getAsBoolean()) {
        return true;
      }

      long now = System.nanoTime();
      long remaining = deadline - now;
      if (remaining <= 0) {
        break;
      }

      long millis = NANOSECONDS.toMillis(remaining);
      long nanos = remaining - MILLISECONDS.toNanos(millis);

      synchronized (monitor) {
        monitor.wait(millis, (int) nanos);
      }
    }

    return false;
  }

  public boolean waitForTracesMax(final int number, int seconds) throws InterruptedException {
    return awaitUntilDeadline(seconds, SECONDS, () -> traceCount.get() >= number);
  }

  public void waitForTraces(final int number) throws InterruptedException, TimeoutException {
    waitForTraces(number, WAIT_FOR_TRACES_TIMEOUT_SECONDS);
  }

  public void waitForTraces(final int number, final int seconds)
      throws InterruptedException, TimeoutException {
    if (!waitForTracesMax(number, seconds)) {
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
    waitUntilReported(span, 20, SECONDS);
  }

  public void waitUntilReported(final DDSpan span, int timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException {
    boolean reported = awaitUntilDeadline(timeout, unit, () -> isReported(span));

    if (!reported) {
      String msg = "Timeout waiting for span to be reported: " + span;
      log.warn(msg);
      throw new TimeoutException(msg);
    }
  }

  /**
   * Set a filter to be applied to all incoming traces to determine whether they should be written
   * or not.
   */
  public void setFilter(Filter filter) {
    this.filter = filter;
  }

  /** Set a {@link MetadataConsumer} to capture what trace metadata would be sent to the agent. */
  public void setMetadataConsumer(MetadataConsumer metadataConsumer) {
    this.metadataConsumer = metadataConsumer;
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
  public void clear() {
    super.clear();

    traceCount.set(0);
  }

  @Override
  public void close() {
    clear();
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
