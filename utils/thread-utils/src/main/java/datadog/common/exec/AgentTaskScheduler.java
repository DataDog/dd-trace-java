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

  private final DelayQueue<PeriodicTask<?>> workQueue = new DelayQueue<>();
  private final ThreadFactory threadFactory;
  private volatile Thread worker;
  private volatile boolean shutdown;

  public AgentTaskScheduler(final ThreadFactory threadFactory) {
    this.threadFactory = threadFactory;
  }

  public <T> void scheduleAtFixedRate(
      final Task<T> task,
      final T target,
      final long initialDelay,
      final long period,
      final TimeUnit unit) {

    if (shutdown) {
      log.warn("Agent task scheduler is shutdown. Will not run {}", describeTask(task, target));
      return;
    }
    if (worker == null) {
      synchronized (this) {
        if (shutdown) {
          log.warn("Agent task scheduler is shutdown. Will not run {}", describeTask(task, target));
          return;
        }
        if (worker == null) {
          Runtime.getRuntime().addShutdownHook(new Shutdown());
          worker = threadFactory.newThread(new Worker());
          worker.start();
        }
      }
    }

    workQueue.offer(new PeriodicTask<>(task, target, initialDelay, period, unit));
  }

  public boolean isShutdown() {
    return shutdown;
  }

  private static <T> String describeTask(final Task<T> task, final T target) {
    return "periodic task "
        + task.getClass().getSimpleName()
        + (target != null ? " for " + target : "");
  }

  private final class Shutdown extends Thread {
    @Override
    @SneakyThrows
    public void run() {
      shutdown = true;
      synchronized (this) {
        if (worker != null) {
          worker.interrupt();
          worker.join(SHUTDOWN_WAIT_MILLIS);
        }
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
            log.warn("Problem running {}", work, e);
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
    private final WeakReference<T> target;
    private final int period;
    private final int taskSequence;
    private long time;

    public PeriodicTask(
        final Task<T> task,
        final T target,
        final long initialDelay,
        final long period,
        final TimeUnit unit) {

      this.task = task;
      this.target = new WeakReference<>(target);
      this.period = (int) unit.toNanos(period);
      taskSequence = TASK_SEQUENCE_GENERATOR.getAndIncrement();
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
      return describeTask(task, target.get());
    }
  }
}
