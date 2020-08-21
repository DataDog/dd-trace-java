package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.common.sampling.PrioritySampling.USER_DROP;

import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public enum Prioritization {
  FAST_LANE {
    @Override
    public PrioritizationStrategy create(Queue<Object> primary, Queue<Object> secondary) {
      return new FastLaneStrategy(primary, secondary);
    }
  },
  DEAD_LETTERS {
    @Override
    public PrioritizationStrategy create(Queue<Object> primary, Queue<Object> secondary) {
      return new DeadLettersStrategy(primary, secondary);
    }
  };

  public abstract PrioritizationStrategy create(Queue<Object> primary, Queue<Object> secondary);

  private static final class FastLaneStrategy implements PrioritizationStrategy {

    private final Queue<Object> primary;
    private final Queue<Object> secondary;

    private FastLaneStrategy(Queue<Object> primary, Queue<Object> secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    @Override
    public boolean publish(int priority, List<DDSpan> trace) {
      switch (priority) {
        case SAMPLER_DROP:
        case USER_DROP:
          return secondary.offer(trace);
        default:
          return primary.offer(trace);
      }
    }

    @Override
    public boolean flush(long timeout, TimeUnit timeUnit) {
      // ok not to flush the secondary
      CountDownLatch latch = new CountDownLatch(1);
      FlushEvent event = new FlushEvent(latch);
      offer(primary, event);
      try {
        return latch.await(timeout, timeUnit);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    private void offer(Queue<Object> queue, FlushEvent event) {
      boolean offered;
      do {
        offered = queue.offer(event);
      } while (!offered);
    }
  }

  private static final class DeadLettersStrategy implements PrioritizationStrategy {

    private final Queue<Object> primary;
    private final Queue<Object> secondary;

    private DeadLettersStrategy(Queue<Object> primary, Queue<Object> secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    @Override
    public boolean publish(int priority, List<DDSpan> trace) {
      if (!primary.offer(trace)) {
        switch (priority) {
          case SAMPLER_DROP:
          case USER_DROP:
            return false;
          default:
            return secondary.offer(trace);
        }
      }
      return true;
    }

    @Override
    public boolean flush(long timeout, TimeUnit timeUnit) {
      // both queues need to be flushed
      CountDownLatch latch = new CountDownLatch(2);
      FlushEvent event = new FlushEvent(latch);
      offer(primary, event);
      offer(secondary, event);
      try {
        return latch.await(timeout, timeUnit);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    private void offer(Queue<Object> queue, FlushEvent event) {
      boolean offered;
      do {
        offered = queue.offer(event);
      } while (!offered);
    }
  }
}
