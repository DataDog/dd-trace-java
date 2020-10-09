package datadog.trace.api.exec;

import java.util.concurrent.ThreadFactory;

/** A {@link ThreadFactory} implementation that starts all {@link Thread} as daemons. */
public final class DaemonThreadFactory implements ThreadFactory {
  public static final DaemonThreadFactory TRACE_MONITOR =
      new DaemonThreadFactory("dd-trace-monitor");
  public static final DaemonThreadFactory TRACE_PROCESSOR =
      new DaemonThreadFactory("dd-trace-processor");
  public static final DaemonThreadFactory TASK_SCHEDULER =
      new DaemonThreadFactory("dd-task-scheduler");

  private final String threadName;
  private final Runnable initializer;

  /**
   * Constructs a new {@code DaemonThreadFactory} with a null ContextClassLoader.
   *
   * @param threadName used to prefix all thread names.
   */
  public DaemonThreadFactory(final String threadName) {
    this(threadName, null);
  }

  /**
   * Constructs a new {@code DaemonThreadFactory} with a null ContextClassLoader.
   *
   * @param threadName used to prefix all thread names.
   * @param initializer initializer runnable that will be executed for all created threads.
   * @note The initializer runnable can be executed by multiple threads if more than one thread is
   *     created by the factory, and should take that into account to avoid race conditions.
   */
  private DaemonThreadFactory(final String threadName, Runnable initializer) {
    this.threadName = threadName;
    this.initializer = initializer;
  }

  /**
   * Constructs a new {@code DaemonThreadFactory} with a null ContextClassLoader.
   *
   * @param initializer initializer runnable that will be executed for all created threads.
   * @note The initializer runnable can be executed by multiple threads if more than one thread is
   *     created by the factory, and should take that into account to avoid race conditions.
   */
  public ThreadFactory withInitializer(Runnable initializer) {
    return new DaemonThreadFactory(this.threadName, initializer);
  }

  @Override
  public Thread newThread(final Runnable r) {
    Runnable runnable = r;
    if (this.initializer != null) {
      runnable =
          new Runnable() {
            @Override
            public void run() {
              initializer.run();
              r.run();
            }
          };
    }
    final Thread thread = new Thread(runnable, threadName);
    thread.setDaemon(true);
    thread.setContextClassLoader(null);
    return thread;
  }
}
