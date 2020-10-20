package datadog.trace.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** A {@link ThreadFactory} implementation that starts all agent {@link Thread}s as daemons. */
public final class AgentThreadFactory implements ThreadFactory {
  public static final ThreadGroup AGENT_THREAD_GROUP = new ThreadGroup("dd-trace-java");

  // known agent threads
  public enum AgentThread {
    TASK_SCHEDULER,
    TRACE_STARTUP,
    TRACE_MONITOR,
    TRACE_PROCESSOR,
    TRACE_CASSANDRA_ASYNC_SESSION,
    JMX_STARTUP,
    JMX_COLLECTOR,
    PROFILER_STARTUP,
    PROFILER_RECORDING_STARTUP,
    PROFILER_RECORDING_SCHEDULER,
    PROFILER_HTTP_DISPATCHER;

    public String threadName() {
      return "dd-" + name().toLowerCase().replace('_', '-');
    }
  }

  private final AgentThread agentThread;

  /**
   * Constructs a new agent {@code ThreadFactory}.
   *
   * @param agentThread the agent thread created by this factory.
   */
  public AgentThreadFactory(final AgentThread agentThread) {
    this.agentThread = agentThread;
  }

  @Override
  public Thread newThread(final Runnable runnable) {
    return newAgentThread(agentThread, runnable);
  }

  /**
   * Constructs a new agent {@code Thread} as a daemon with a null ContextClassLoader.
   *
   * @param agentThread the agent thread to create.
   * @param runnable work to run on the new thread.
   */
  public static Thread newAgentThread(final AgentThread agentThread, final Runnable runnable) {
    final Thread thread = new Thread(AGENT_THREAD_GROUP, runnable, agentThread.threadName());
    thread.setDaemon(true);
    thread.setContextClassLoader(null);
    return thread;
  }

  /**
   * Constructs a delayed agent {@code Thread} as a daemon with a null ContextClassLoader.
   *
   * @param agentThread the agent thread to create.
   * @param runnable work to run on the new thread.
   * @param initialDelay delay before starting work.
   */
  public static Thread delayedAgentThread(
      final AgentThread agentThread,
      final Runnable runnable,
      final long initialDelay,
      final TimeUnit unit) {
    return newAgentThread(
        agentThread,
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
