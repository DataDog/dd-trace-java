package datadog.trace.util;

import java.util.concurrent.ThreadFactory;

/** A {@link ThreadFactory} implementation that starts all agent {@link Thread}s as daemons. */
public final class AgentThreadFactory implements ThreadFactory {
  public static final ThreadGroup AGENT_THREAD_GROUP = new ThreadGroup("dd-trace-java");

  public static final AgentThreadFactory TRACE_MONITOR = new AgentThreadFactory("dd-trace-monitor");
  public static final AgentThreadFactory TRACE_PROCESSOR =
      new AgentThreadFactory("dd-trace-processor");
  public static final AgentThreadFactory TASK_SCHEDULER =
      new AgentThreadFactory("dd-task-scheduler");

  private final String threadName;
  private final Runnable initializer;

  /**
   * Constructs a new {@code AgentThreadFactory} with a null ContextClassLoader.
   *
   * @param threadName used to prefix all thread names.
   */
  public AgentThreadFactory(final String threadName) {
    this(threadName, null);
  }

  /**
   * Constructs a new {@code AgentThreadFactory} with a null ContextClassLoader.
   *
   * @param threadName used to prefix all thread names.
   * @param initializer initializer runnable that will be executed for all created threads.
   * @note The initializer runnable can be executed by multiple threads if more than one thread is
   *     created by the factory, and should take that into account to avoid race conditions.
   */
  private AgentThreadFactory(final String threadName, Runnable initializer) {
    this.threadName = threadName;
    this.initializer = initializer;
  }

  /**
   * Constructs a new {@code AgentThreadFactory} with a null ContextClassLoader.
   *
   * @param initializer initializer runnable that will be executed for all created threads.
   * @note The initializer runnable can be executed by multiple threads if more than one thread is
   *     created by the factory, and should take that into account to avoid race conditions.
   */
  public ThreadFactory withInitializer(Runnable initializer) {
    return new AgentThreadFactory(this.threadName, initializer);
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
    final Thread thread = new Thread(AGENT_THREAD_GROUP, runnable, threadName);
    thread.setDaemon(true);
    thread.setContextClassLoader(null);
    return thread;
  }
}
