package datadog.trace.util;

import java.util.concurrent.ThreadFactory;
import org.slf4j.LoggerFactory;

/** A {@link ThreadFactory} implementation that starts all agent {@link Thread}s as daemons. */
public final class AgentThreadFactory implements ThreadFactory {
  public static final ThreadGroup AGENT_THREAD_GROUP = new ThreadGroup("dd-trace-java");

  public static final long THREAD_JOIN_TIMOUT_MS = 800;

  // known agent threads
  public enum AgentThread {
    TASK_SCHEDULER("dd-task-scheduler"),

    TRACE_STARTUP("dd-agent-startup-datadog-tracer"),
    TRACE_MONITOR("dd-trace-monitor"),
    TRACE_PROCESSOR("dd-trace-processor"),
    SPAN_SAMPLING_PROCESSOR("dd-span-sampling-processor"),
    TRACE_CASSANDRA_ASYNC_SESSION("dd-cassandra-session-executor"),

    METRICS_AGGREGATOR("dd-metrics-aggregator"),
    STATSD_CLIENT("dd-statsd-client"),

    JMX_STARTUP("dd-agent-startup-jmxfetch"),
    JMX_COLLECTOR("dd-jmx-collector"),

    PROFILER_STARTUP("dd-agent-startup-datadog-profiler"),
    PROFILER_RECORDING_SCHEDULER("dd-profiler-recording-scheduler"),
    PROFILER_HTTP_DISPATCHER("dd-profiler-http-dispatcher"),

    APPSEC_HTTP_DISPATCHER("dd-appsec-http-dispatcher"),

    TELEMETRY("dd-telemetry"),

    FLEET_MANAGEMENT_POLLER("dd-fleet-management-poller"),
    REMOTE_CONFIG("dd-remote-config"),

    CWS_TLS("dd-cws-tls"),

    PROCESS_SUPERVISOR("dd-process-supervisor"),

    DATA_STREAMS_MONITORING("dd-data-streams-monitor"),

    DEBUGGER_HTTP_DISPATCHER("dd-debugger-upload-http-dispatcher"),

    CI_SHELL_COMMAND("dd-ci-shell-command"),
    CI_GIT_DATA_UPLOADER("dd-ci-git-data-uploader"),
    CI_GIT_DATA_SHUTDOWN_HOOK("dd-ci-git-data-shutdown-hook"),
    CI_TEST_EVENTS_SHUTDOWN_HOOK("dd-ci-test-events-shutdown-hook"),
    CI_PROJECT_CONFIGURATOR("dd-ci-project-configurator"),
    CI_SIGNAL_SERVER("dd-ci-signal-server");

    public final String threadName;

    AgentThread(final String threadName) {
      this.threadName = threadName;
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
    return newAgentThread(agentThread, null, runnable, true);
  }

  public static Thread newAgentThread(
      final AgentThread agentThread, final Runnable runnable, boolean daemon) {
    return newAgentThread(agentThread, null, runnable, daemon);
  }

  public static Thread newAgentThread(
      final AgentThread agentThread,
      final String nameSuffix,
      final Runnable runnable,
      boolean daemon) {
    final String threadName =
        nameSuffix != null ? agentThread.threadName + nameSuffix : agentThread.threadName;
    final Thread thread = new Thread(AGENT_THREAD_GROUP, runnable, threadName);
    thread.setDaemon(daemon);
    thread.setContextClassLoader(null);
    thread.setUncaughtExceptionHandler(
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(final Thread thread, final Throwable e) {
            LoggerFactory.getLogger(runnable.getClass())
                .error("Uncaught exception in {}", agentThread.threadName, e);
          }
        });
    return thread;
  }
}
