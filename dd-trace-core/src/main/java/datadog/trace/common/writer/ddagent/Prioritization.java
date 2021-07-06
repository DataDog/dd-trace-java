package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;

import datadog.communication.ddagent.DroppingPolicy;
import datadog.trace.core.CoreSpan;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public enum Prioritization {
  ENSURE_TRACE {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary, final Queue<Object> secondary, DroppingPolicy neverUsed) {
      return new EnsureTraceStrategy(primary, secondary);
    }
  },
  FAST_LANE {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary, final Queue<Object> secondary, DroppingPolicy droppingPolicy) {
      return new FastLaneStrategy(primary, secondary, droppingPolicy);
    }
  };

  public abstract PrioritizationStrategy create(
      Queue<Object> primary, Queue<Object> secondary, DroppingPolicy droppingPolicy);

  private abstract static class PrioritizationStrategyWithFlush implements PrioritizationStrategy {

    protected final Queue<Object> primary;

    protected PrioritizationStrategyWithFlush(Queue<Object> primary) {
      this.primary = primary;
    }

    @Override
    public boolean flush(final long timeout, final TimeUnit timeUnit) {
      // ok not to flush the secondary
      final CountDownLatch latch = new CountDownLatch(1);
      final FlushEvent event = new FlushEvent(latch);
      blockingOffer(primary, event);
      try {
        return latch.await(timeout, timeUnit);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    protected void blockingOffer(final Queue<Object> queue, final Object event) {
      boolean offered;
      do {
        offered = queue.offer(event);
      } while (!offered);
    }
  }

  private static final class EnsureTraceStrategy extends PrioritizationStrategyWithFlush {

    private final Queue<Object> secondary;

    private EnsureTraceStrategy(final Queue<Object> primary, final Queue<Object> secondary) {
      super(primary);
      this.secondary = secondary;
    }

    @Override
    public <T extends CoreSpan<T>> boolean publish(T root, int priority, final List<T> trace) {
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          return secondary.offer(trace);
        default:
          blockingOffer(primary, trace);
          return true;
      }
    }
  }

  private static final class FastLaneStrategy extends PrioritizationStrategyWithFlush {

    private final Queue<Object> secondary;
    private final DroppingPolicy droppingPolicy;

    private FastLaneStrategy(
        final Queue<Object> primary, final Queue<Object> secondary, DroppingPolicy droppingPolicy) {
      super(primary);
      this.secondary = secondary;
      this.droppingPolicy = droppingPolicy;
    }

    @Override
    public <T extends CoreSpan<T>> boolean publish(T root, int priority, List<T> trace) {
      if (root.isForceKeep()) {
        return primary.offer(trace);
      }
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          return !droppingPolicy.active() && secondary.offer(trace);
        default:
          return primary.offer(trace);
      }
    }
  }
}
