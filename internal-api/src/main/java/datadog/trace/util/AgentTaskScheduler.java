package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.util.AgentThreadFactory.AgentThread;
import java.lang.ref.WeakReference;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AgentTaskScheduler implements Executor {
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

  public static final class Scheduled<T> implements Target<T> {
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
      log.warn("Agent task scheduler is shutdown. Will not run {}", describeTask(task, target));
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

  // visibleForTesting
  int taskCount() {
    return workQueue.size();
  }

  public boolean isShutdown() {
    return shutdown;
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
            log.warn("Uncaught exception from {}", work, e);
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
    public String toString() {
      return describeTask(task, target);
    }
  }
}
