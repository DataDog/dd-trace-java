package datadog.trace.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@link ThreadFactory} implementation that starts all agent {@link Thread}s as daemons. */
public final class AgentThreadFactory implements ThreadFactory {
  public static final ThreadGroup AGENT_THREAD_GROUP = new ThreadGroup("dd-trace-java");

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
    return newAgentThread(threadPrefix + threadCount.incrementAndGet(), runnable);
  }

  /**
   * Constructs a new agent {@code Thread} as a daemon with a null ContextClassLoader.
   *
   * @param threadName name of the new thread.
   * @param runnable work to run on the new thread.
   */
  public static Thread newAgentThread(final String threadName, final Runnable runnable) {
    final Thread thread = new Thread(AGENT_THREAD_GROUP, runnable, threadName);
    thread.setDaemon(true);
    thread.setContextClassLoader(null);
    return thread;
  }

  /**
   * Constructs a delayed agent {@code Thread} as a daemon with a null ContextClassLoader.
   *
   * @param threadName name of the new thread.
   * @param runnable work to run on the new thread.
   * @param initialDelay delay before starting work.
   */
  public static Thread delayedAgentThread(
      final String threadName,
      final Runnable runnable,
      final long initialDelay,
      final TimeUnit unit) {
    return newAgentThread(
        threadName,
        new Runnable() {
          @Override
          public void run() {
            try {
              Thread.sleep(unit.toMillis(initialDelay));
            } catch (final InterruptedException e) {
              // drop-through and start the work
            }
            runnable.run();
          }
        });
  }
}
