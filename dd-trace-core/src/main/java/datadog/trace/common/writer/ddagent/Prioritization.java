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
  /**
   * Blocks the publishing thread until a kept trace fits, and so never drops one. Not for
   * production. It is outside the span budget: blocking on a stalled agent is the behavior it asks
   * for.
   */
  ENSURE_TRACE {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary,
        final Queue<Object> secondary,
        final Queue<Object> spanSampling,
        SpanBudget ignored,
        DroppingPolicy neverUsed) {
      return new EnsureTraceStrategy(primary, secondary, spanSampling);
    }

    @Override
    public SpanBudget spanBudget(long spanLimit) {
      return SpanBudget.unlimited();
    }
  },
  FAST_LANE {
    @Override
    public PrioritizationStrategy create(
        final Queue<Object> primary,
        final Queue<Object> secondary,
        final Queue<Object> spanSampling,
        SpanBudget budget,
        DroppingPolicy droppingPolicy) {
      return new FastLaneStrategy(primary, secondary, spanSampling, budget, droppingPolicy);
    }

    @Override
    public SpanBudget spanBudget(long spanLimit) {
      return SpanBudget.of(spanLimit);
    }
  };

  /**
   * @param budget the span budget the strategy charges; whoever consumes the queues must release
   *     what was charged
   */
  public abstract PrioritizationStrategy create(
      Queue<Object> primary,
      Queue<Object> secondary,
      Queue<Object> spanSampling,
      SpanBudget budget,
      DroppingPolicy droppingPolicy);

  /** The span budget for a strategy of this type, given the configured span limit. */
  public abstract SpanBudget spanBudget(long spanLimit);

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
      if (root.isForceKeep()) {
        blockingOffer(primary, trace);
        return PublishResult.ENQUEUED_FOR_SERIALIZATION;
      }
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          if (spanSampling != null) {
            // send dropped traces for single span sampling
            return spanSampling.offer(trace)
                ? PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
                : PublishResult.DROPPED_BUFFER_OVERFLOW_SINGLE_SPAN;
          }
          return secondary.offer(trace)
              ? PublishResult.ENQUEUED_FOR_SERIALIZATION
              : PublishResult.DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT;
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
    private final SpanBudget budget;

    private FastLaneStrategy(
        final Queue<Object> primary,
        final Queue<Object> secondary,
        final Queue<Object> spanSampling,
        SpanBudget budget,
        DroppingPolicy droppingPolicy) {
      super(primary);
      this.secondary = secondary;
      this.spanSampling = spanSampling;
      this.droppingPolicy = droppingPolicy;
      this.budget = budget;
    }

    @Override
    public <T extends CoreSpan<T>> PublishResult publish(T root, int priority, List<T> trace) {
      final int spans = trace.size();
      if (root.isForceKeep()) {
        return enqueue(primary, trace, budget.tryAdmitForceKept(spans))
            ? PublishResult.ENQUEUED_FOR_SERIALIZATION
            : PublishResult.DROPPED_BUFFER_OVERFLOW;
      }
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          if (spanSampling != null) {
            // send dropped traces for single span sampling
            return enqueue(spanSampling, trace, budget.tryAdmitSampledOut(spans))
                ? PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
                : PublishResult.DROPPED_BUFFER_OVERFLOW_SINGLE_SPAN;
          }
          if (droppingPolicy.active()) {
            return PublishResult.DROPPED_BY_POLICY;
          }
          return enqueue(secondary, trace, budget.tryAdmitSampledOut(spans))
              ? PublishResult.ENQUEUED_FOR_SERIALIZATION
              : PublishResult.DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT;
        default:
          return enqueue(primary, trace, budget.tryAdmitKept(spans))
              ? PublishResult.ENQUEUED_FOR_SERIALIZATION
              : PublishResult.DROPPED_BUFFER_OVERFLOW;
      }
    }

    /** Offers an admitted trace, returning its spans to the budget if the queue is full. */
    private boolean enqueue(Queue<Object> queue, List<?> trace, boolean admitted) {
      if (!admitted) {
        return false;
      }
      if (queue.offer(trace)) {
        return true;
      }
      budget.release(trace.size());
      return false;
    }
  }
}
