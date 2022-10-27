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
        final Queue<Object> primary,
        final Queue<Object> secondary,
        final Queue<Object> spanSampling,
        DroppingPolicy neverUsed) {
      return new EnsureTraceStrategy(primary, secondary, spanSampling);
    }
  },
  FAST_LANE {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary,
        final Queue<Object> secondary,
        final Queue<Object> spanSampling,
        DroppingPolicy droppingPolicy) {
      return new FastLaneStrategy(primary, secondary, spanSampling, droppingPolicy);
    }
  };

  public abstract PrioritizationStrategy create(
      Queue<Object> primary,
      Queue<Object> secondary,
      Queue<Object> spanSampling,
      DroppingPolicy droppingPolicy);

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
    private final Queue<Object> spanSampling;

    private EnsureTraceStrategy(
        final Queue<Object> primary,
        final Queue<Object> secondary,
        final Queue<Object> spanSampling) {
      super(primary);
      this.secondary = secondary;
      this.spanSampling = spanSampling;
    }

    @Override
    public <T extends CoreSpan<T>> PublishResult publish(
        T root, int priority, final List<T> trace) {
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          if (spanSampling != null) {
            // send dropped traces for single span sampling
            return spanSampling.offer(trace)
                ? PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
                : PublishResult.DROPPED_BUFFER_OVERFLOW;
          }
          return secondary.offer(trace)
              ? PublishResult.ENQUEUED_FOR_SERIALIZATION
              : PublishResult.DROPPED_BUFFER_OVERFLOW;
        default:
          blockingOffer(primary, trace);
          return PublishResult.ENQUEUED_FOR_SERIALIZATION;
      }
    }
  }

  private static final class FastLaneStrategy extends PrioritizationStrategyWithFlush {

    private final Queue<Object> secondary;
    private final Queue<Object> spanSampling;
    private final DroppingPolicy droppingPolicy;

    private FastLaneStrategy(
        final Queue<Object> primary,
        final Queue<Object> secondary,
        final Queue<Object> spanSampling,
        DroppingPolicy droppingPolicy) {
      super(primary);
      this.secondary = secondary;
      this.spanSampling = spanSampling;
      this.droppingPolicy = droppingPolicy;
    }

    @Override
    public <T extends CoreSpan<T>> PublishResult publish(T root, int priority, List<T> trace) {
      if (root.isForceKeep()) {
        return primary.offer(trace)
            ? PublishResult.ENQUEUED_FOR_SERIALIZATION
            : PublishResult.DROPPED_BUFFER_OVERFLOW;
      }
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          if (spanSampling != null) {
            // send dropped traces for single span sampling
            return spanSampling.offer(trace)
                ? PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
                : PublishResult.DROPPED_BUFFER_OVERFLOW;
          }
          if (droppingPolicy.active()) {
            return PublishResult.DROPPED_BY_POLICY;
          }
          return secondary.offer(trace)
              ? PublishResult.ENQUEUED_FOR_SERIALIZATION
              : PublishResult.DROPPED_BUFFER_OVERFLOW;
        default:
          return primary.offer(trace)
              ? PublishResult.ENQUEUED_FOR_SERIALIZATION
              : PublishResult.DROPPED_BUFFER_OVERFLOW;
      }
    }
  }
}
