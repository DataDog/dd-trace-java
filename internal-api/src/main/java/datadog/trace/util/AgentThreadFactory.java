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

  /**
   * Constructs a new {@code AgentThreadFactory} with a null ContextClassLoader.
   *
   * @param threadName used to prefix all thread names.
   */
  public AgentThreadFactory(final String threadName) {
    this.threadName = threadName;
  }

  @Override
  public Thread newThread(final Runnable runnable) {
    final Thread thread = new Thread(AGENT_THREAD_GROUP, runnable, threadName);
    thread.setDaemon(true);
    thread.setContextClassLoader(null);
    return thread;
  }
}
