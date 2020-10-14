package datadog.common.exec;

import static datadog.common.exec.DaemonThreadFactory.TASK_SCHEDULER;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.lang.ref.WeakReference;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AgentTaskScheduler {
  public static final AgentTaskScheduler INSTANCE = new AgentTaskScheduler(TASK_SCHEDULER);

  private static final long SHUTDOWN_WAIT_MILLIS = 5_000;

  public interface Task<T> {
    void run(T target);
  }

  public interface Target<T> {
    T get();
  }

  private static final class WeakTarget<T> extends WeakReference<T> implements Target<T> {
    public WeakTarget(final T referent) {
      super(referent);
    }
  }

  private final DelayQueue<PeriodicTask<?>> workQueue = new DelayQueue<>();
  private final ThreadFactory threadFactory;
  private volatile Thread worker;
  private volatile boolean shutdown;

  public AgentTaskScheduler(final ThreadFactory threadFactory) {
    this.threadFactory = threadFactory;
  }

  public <T> void weakScheduleAtFixedRate(
      final Task<T> task,
      final T target,
      final long initialDelay,
      final long period,
      final TimeUnit unit) {
    scheduleAtFixedRate(task, new WeakTarget<>(target), initialDelay, period, unit);
  }

  public <T> void scheduleAtFixedRate(
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
            worker = threadFactory.newThread(new Worker());
            // register hook after worker is assigned, but before we start it
            Runtime.getRuntime().addShutdownHook(new Shutdown());
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

  public boolean isShutdown() {
    return shutdown;
  }

  private static <T> String describeTask(final Task<T> task, final Target<T> target) {
    return "periodic task " + task.getClass().getSimpleName() + " with target " + target.get();
  }

  private final class Shutdown extends Thread {
    @Override
    @SneakyThrows
    public void run() {
      shutdown = true;
      final Thread t = worker;
      if (t != null) {
        t.interrupt();
        t.join(SHUTDOWN_WAIT_MILLIS);
      }
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
      worker = null;
    }
  }

  private static final AtomicInteger TASK_SEQUENCE_GENERATOR = new AtomicInteger();

  private static final class PeriodicTask<T> implements Delayed {

    private final Task<T> task;
    private final Target<T> target;
    private final int period;
    private final int taskSequence;

    private long time;

    public PeriodicTask(
        final Task<T> task,
        final Target<T> target,
        final long initialDelay,
        final long period,
        final TimeUnit unit) {

      this.task = task;
      this.target = target;
      this.period = (int) unit.toNanos(period);
      this.taskSequence = TASK_SEQUENCE_GENERATOR.getAndIncrement();

      time = System.nanoTime() + unit.toNanos(initialDelay);
    }

    public void run() {
      final T t = target.get();
      if (t != null) {
        task.run(t);
      }
    }

    public boolean reschedule() {
      if (target.get() != null) {
        time += period;
        return true;
      }
      return false;
    }

    @Override
    public long getDelay(final TimeUnit unit) {
      return unit.convert(time - System.nanoTime(), NANOSECONDS);
    }

    @Override
    public int compareTo(final Delayed other) {
      if (this == other) {
        return 0;
      }
      long taskOrder;
      if (other instanceof PeriodicTask<?>) {
        final PeriodicTask<?> otherTask = (PeriodicTask<?>) other;
        taskOrder = time - otherTask.time;
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
