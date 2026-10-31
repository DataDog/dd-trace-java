package datadog.trace.core;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import datadog.trace.api.Config;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

@JCStressTest
@Outcome(id = "1", expect = ACCEPTABLE, desc = "The caller's flush wrote its accepted element.")
@Outcome(id = "0", expect = FORBIDDEN, desc = "A concurrent marker released flush too early.")
@State
public class PendingTraceBufferConcurrentFlushStressTest {
  private final PendingTraceBuffer.DelayingPendingTraceBuffer buffer =
      new PendingTraceBuffer.DelayingPendingTraceBuffer(
          16, SystemTimeSource.INSTANCE, Config.get(), null, HealthMetrics.NO_OP);
  private final RecordingElement element = new RecordingElement();

  public PendingTraceBufferConcurrentFlushStressTest() {
    buffer.getWorker().start();
  }

  @Actor
  public void competingFlush() {
    buffer.flush();
  }

  @Actor
  public void enqueueThenFlush(I_Result result) {
    buffer.enqueue(element);
    buffer.flush();
    result.r1 = element.writes.get();
  }

  @Arbiter
  public void close() {
    buffer.close();
  }

  private static final class RecordingElement implements PendingTraceBuffer.Element {
    private final AtomicBoolean enqueued = new AtomicBoolean();
    private final AtomicInteger writes = new AtomicInteger();

    @Override
    public long oldestFinishedTime() {
      return Long.MAX_VALUE;
    }

    @Override
    public boolean lastReferencedNanosAgo(long nanos) {
      return false;
    }

    @Override
    public void write() {
      writes.incrementAndGet();
    }

    @Override
    public DDSpan getRootSpan() {
      return null;
    }

    @Override
    public boolean setEnqueued(boolean newValue) {
      return enqueued.compareAndSet(!newValue, newValue);
    }

    @Override
    public boolean writeOnBufferFull() {
      return true;
    }
  }
}
