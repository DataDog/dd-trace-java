package datadog.trace.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@link ThreadFactory} implementation that starts all agent {@link Thread}s as daemons. */
public final class AgentThreadFactory implements ThreadFactory {
  public static final ThreadGroup AGENT_THREAD_GROUP = new ThreadGroup("dd-trace-java");

  public static final AgentThreadFactory TRACE_MONITOR = new AgentThreadFactory("dd-trace-monitor");
  public static final AgentThreadFactory TRACE_PROCESSOR =
      new AgentThreadFactory("dd-trace-processor");
  public static final AgentThreadFactory TASK_SCHEDULER =
      new AgentThreadFactory("dd-task-scheduler");

  private final AtomicInteger threadCount = new AtomicInteger(0);
  private final String threadPrefix;

  /**
   * Constructs a new agent {@code ThreadFactory}.
   *
   * @param threadPrefix used to prefix all thread names.
   */
  public AgentThreadFactory(final String threadPrefix) {
    this.threadPrefix = threadPrefix;
  }

  @Override
  public Thread newThread(final Runnable runnable) {
    return newAgentThread(runnable, threadPrefix + threadCount.incrementAndGet());
  }

  /**
   * Constructs a new agent {@code Thread} with a null ContextClassLoader.
   *
   * @param threadPrefix used to prefix all thread names.
   */
  public static Thread newAgentThread(final Runnable runnable, final String threadPrefix) {
    final Thread thread = new Thread(AGENT_THREAD_GROUP, runnable, threadPrefix);
    thread.setDaemon(true);
    thread.setContextClassLoader(null);
    return thread;
  }
}
