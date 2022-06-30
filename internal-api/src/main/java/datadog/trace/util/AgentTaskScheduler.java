package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.util.AgentThreadFactory.AgentThread;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.ref.WeakReference;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentTaskScheduler implements Executor {
  private static final Logger log = LoggerFactory.getLogger(AgentTaskScheduler.class);
  public static final AgentTaskScheduler INSTANCE = new AgentTaskScheduler(TASK_SCHEDULER);

  private static final long SHUTDOWN_TIMEOUT = 5; // seconds

  public interface Task<T> {
    void run(T target);
  }

  public interface Target<T> {
    T get();
  }

  public static final class RunnableTask implements Task<Runnable> {
    public static final RunnableTask INSTANCE = new RunnableTask();

    @Override
    public void run(final Runnable target) {
      target.run();
    }
  }

  public static class Scheduled<T> implements Target<T> {
    private volatile T referent;

    private Scheduled(final T referent) {
      this.referent = referent;
    }

    @Override
    public T get() {
      return referent;
    }

    public void cancel() {
      referent = null;
    }
  }

  private static final class WeakTarget<T> extends WeakReference<T> implements Target<T> {
    private WeakTarget(final T referent) {
      super(referent);
    }
  }

  private final DelayQueue<PeriodicTask<?>> workQueue = new DelayQueue<>();
  private final AgentThread agentThread;
  private volatile Thread worker;
  private volatile boolean shutdown;

  public AgentTaskScheduler(final AgentThread agentThread) {
    this.agentThread = agentThread;
  }

  @Override
  public void execute(final Runnable target) {
    schedule(RunnableTask.INSTANCE, target, 0, MILLISECONDS);
  }

  public <T> Scheduled<T> schedule(
      final Task<T> task, final T target, final long initialDelay, final TimeUnit unit) {
    final Scheduled<T> scheduled = new Scheduled<>(target);
    scheduleTarget(task, scheduled, initialDelay, 0, unit);
    return scheduled;
  }

  /**
   * Adds a random jitter of up to 10 seconds to the delay. This avoids a fleet of traced
   * applications starting at the same time and scheduling the same publishing task in sync
   */
  public <T> Scheduled<T> scheduleWithJitter(
      final Task<T> task, final T target, final long initialDelay, final TimeUnit unit) {

    // schedule to start after geometrically distributed number of seconds expressed in
    // milliseconds, with p = 0.25, meaning the probability that the aggregator will not
    // have started by the nth second is 0.25(0.75)^n-1 (or a 1% chance of not having
    // started within 10 seconds, where a cap is applied)
    long randomMillis =
        unit.toMillis(initialDelay)
            + Math.min(
                (long)
                    (1000D
                        * Math.log(ThreadLocalRandom.current().nextDouble())
                        / Math.log(1 - 0.25)),
                10_000);

    return schedule(task, target, randomMillis, MILLISECONDS);
  }

  public <T> Scheduled<T> scheduleAtFixedRate(
      final Task<T> task,
      final T target,
      final long initialDelay,
      final long period,
      final TimeUnit unit) {
    final Scheduled<T> scheduled = new Scheduled<>(target);
    scheduleTarget(task, scheduled, initialDelay, period, unit);
    return scheduled;
  }

  public <T> void weakScheduleAtFixedRate(
      final Task<T> task,
      final T target,
      final long initialDelay,
      final long period,
      final TimeUnit unit) {
    scheduleTarget(task, new WeakTarget<>(target), initialDelay, period, unit);
  }

  @SuppressFBWarnings("JLM_JSR166_UTILCONCURRENT_MONITORENTER")
  private <T> void scheduleTarget(
      final Task<T> task,
      final Target<T> target,
      final long initialDelay,
      final long period,
      final TimeUnit unit) {

    if (target == null || target.get() == null) {
      return;
    }

    if (!shutdown && worker == null) {
      synchronized (workQueue) {
        if (!shutdown && worker == null) {
          prepareWorkQueue();
          try {
            worker = newAgentThread(agentThread, new Worker());
            // register hook after worker is assigned, but before we start it
            Runtime.getRuntime().addShutdownHook(new ShutdownHook());
            worker.start();
          } catch (final IllegalStateException e) {
            shutdown = true; // couldn't add hook, JVM is shutting down
          }
        }
      }
    }

    if (!shutdown) {
      workQueue.offer(new PeriodicTask<>(task, target, initialDelay, period, unit));
    } else {
      log.debug("Agent task scheduler is shutdown. Will not run {}", describeTask(task, target));
    }
  }

  private void prepareWorkQueue() {
    try {
      // exercise 'poll' method to make sure all relevant queue synchronizer types are preloaded
      // here rather than in the Worker when it calls 'take' - this avoids a potential loop-back
      workQueue.poll(1, NANOSECONDS);
    } catch (final InterruptedException e) {
      // ignore, we only want to preload queue internals
    }
  }

  // for testing
  int taskCount() {
    return workQueue.size();
  }

  public boolean isShutdown() {
    return shutdown;
  }

  public static void initialize() {
    AgentTaskScheduler.INSTANCE.dummy();
  }

  private void dummy() {
    /*
     * Dummy empty method body used only to make compiler happy when initializing this class by accessing
     * the static INSTANCE field.
     */
  }

  public void shutdown(final long timeout, final TimeUnit unit) {
    shutdown = true;
    final Thread t = worker;
    if (t != null) {
      t.interrupt();
      if (timeout > 0) {
        try {
          t.join(unit.toMillis(timeout));
        } catch (final InterruptedException e) {
          // continue shutdown...
        }
      }
    }
  }

  private static <T> String describeTask(final Task<T> task, final Target<T> target) {
    return "periodic task " + task.getClass().getSimpleName() + " with target " + target.get();
  }

  private final class ShutdownHook extends Thread {
    ShutdownHook() {
      super(AGENT_THREAD_GROUP, agentThread.threadName + "-shutdown-hook");
    }

    @Override
    public void run() {
      shutdown(SHUTDOWN_TIMEOUT, SECONDS);
    }
  }

  private final class Worker implements Runnable {
    @Override
    public void run() {
      while (!shutdown) {
        PeriodicTask<?> work = null;
        try {
          work = workQueue.take();
          work.run();
        } catch (final Throwable e) {
          if (work != null) {
            log.debug("Uncaught exception from {}", work, e);
          }
        } finally {
          if (work != null && work.reschedule()) {
            workQueue.offer(work);
          }
        }
      }
      workQueue.clear();
      worker = null;
    }
  }

  private static final AtomicInteger TASK_SEQUENCE_GENERATOR = new AtomicInteger();

  private static final class PeriodicTask<T> implements Delayed {

    private final Task<T> task;
    private final Target<T> target;
    private final long period;
    private final int taskSequence;

    private long nextFireTime;

    public PeriodicTask(
        final Task<T> task,
        final Target<T> target,
        final long initialDelay,
        final long period,
        final TimeUnit unit) {

      this.task = task;
      this.target = target;
      this.period = unit.toNanos(period);
      this.taskSequence = TASK_SEQUENCE_GENERATOR.getAndIncrement();

      nextFireTime = System.nanoTime() + unit.toNanos(initialDelay);
    }

    public void run() {
      final T t = target.get();
      if (t != null) {
        task.run(t);
      }
    }

    public boolean reschedule() {
      if (period > 0 && target.get() != null) {
        nextFireTime += period;
        return true;
      }
      return false;
    }

    @Override
    public long getDelay(final TimeUnit unit) {
      return unit.convert(nextFireTime - System.nanoTime(), NANOSECONDS);
    }

    @Override
    public int compareTo(final Delayed other) {
      if (this == other) {
        return 0;
      }
      long taskOrder;
      if (other instanceof PeriodicTask<?>) {
        final PeriodicTask<?> otherTask = (PeriodicTask<?>) other;
        taskOrder = nextFireTime - otherTask.nextFireTime;
        if (taskOrder == 0) {
          taskOrder = taskSequence - otherTask.taskSequence;
        }
      } else {
        taskOrder = getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS);
      }
      return taskOrder < 0 ? -1 : (taskOrder > 0 ? 1 : 0);
    }

    @Override
    public boolean equals(Object obj) {
      try {
        return obj == null ? false : (this.compareTo((Delayed) obj) != 0 ? false : true);
      } catch (ClassCastException e) {
        return false;
      }
    }

    @Override
    public String toString() {
      return describeTask(task, target);
    }
  }
}
