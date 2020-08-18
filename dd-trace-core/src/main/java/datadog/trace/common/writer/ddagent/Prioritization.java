package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.common.sampling.PrioritySampling.USER_DROP;

import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MessagePassingQueue;

public enum Prioritization {
  FAST_LANE {
    @Override
    public PrioritizationStrategy create(
        MessagePassingQueue<Object> primary, MessagePassingQueue<Object> secondary) {
      return new FastLaneStrategy(primary, secondary);
    }
  },
  DEAD_LETTERS {
    @Override
    public PrioritizationStrategy create(
        MessagePassingQueue<Object> primary, MessagePassingQueue<Object> secondary) {
      return new DeadLettersStrategy(primary, secondary);
    }
  };

  public abstract PrioritizationStrategy create(
      MessagePassingQueue<Object> primary, MessagePassingQueue<Object> secondary);

  private static final class FastLaneStrategy implements PrioritizationStrategy {

    private final MessagePassingQueue<Object> primary;
    private final MessagePassingQueue<Object> secondary;

    private FastLaneStrategy(
        MessagePassingQueue<Object> primary, MessagePassingQueue<Object> secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    @Override
    public boolean published(int priority, List<DDSpan> trace) {
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

    private void offer(MessagePassingQueue<Object> queue, FlushEvent event) {
      boolean offered;
      do {
        offered = queue.offer(event);
      } while (!offered);
    }
  }

  private static final class DeadLettersStrategy implements PrioritizationStrategy {

    private final MessagePassingQueue<Object> primary;
    private final MessagePassingQueue<Object> secondary;

    private DeadLettersStrategy(
        MessagePassingQueue<Object> primary, MessagePassingQueue<Object> secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    @Override
    public boolean published(int priority, List<DDSpan> trace) {
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

    private void offer(MessagePassingQueue<Object> queue, FlushEvent event) {
      boolean offered;
      do {
        offered = queue.offer(event);
      } while (!offered);
    }
  }
}
