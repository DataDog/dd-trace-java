package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROCESS_SUPERVISOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.ProcessSupervisor.Health.FAULTED;
import static datadog.trace.util.ProcessSupervisor.Health.HEALTHY;
import static datadog.trace.util.ProcessSupervisor.Health.INTERRUPTED;
import static datadog.trace.util.ProcessSupervisor.Health.READY_TO_START;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.io.Closeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts an external process and restarts the process if it dies */
public class ProcessSupervisor implements Closeable {

  public enum Health {
    NEVER_CHECKED,
    READY_TO_START,
    INTERRUPTED,
    FAULTED,
    HEALTHY
  }

  @FunctionalInterface
  public interface HealthCheck {
    Health run(Health previousHealth) throws InterruptedException;
  }

  public static final HealthCheck ALWAYS_READY = health -> READY_TO_START;

  private static final Logger log = LoggerFactory.getLogger(ProcessSupervisor.class);

  private static final long HEALTHY_DELAY_MILLIS = 10_000;
  private static final long FAULTED_DELAY_MILLIS = 2_000;
  private static final long MAX_FAULTED_DELAY_MILLIS = 60_000;

  // Lifecycle events are logged at info level. A process that keeps failing quickly would flood
  // the log, so after this many failures in a row the details go to debug level until the
  // process stays up for STABLE_RUN_MILLIS.
  private static final int QUIET_AFTER_FAILURES = 5;
  private static final long STABLE_RUN_MILLIS = 60_000;

  private static final long STOP_WAIT_MILLIS = 200;

  private final String imageName;
  private final ProcessBuilder processBuilder;
  private final HealthCheck healthCheck;
  private final Thread supervisorThread;

  private long nextCheckMillis = 0;
  private Health currentHealth = Health.NEVER_CHECKED;
  private Health lastCheckResult = Health.NEVER_CHECKED;
  private Process currentProcess;
  private final FaultBackoff faultBackoff = new FaultBackoff();
  private int failuresInARow;

  private volatile boolean stopping = false;

  /**
   * @param imageName For logging purposes
   * @param processBuilder Builder to create the process
   */
  public ProcessSupervisor(String imageName, ProcessBuilder processBuilder) {
    this(imageName, processBuilder, ALWAYS_READY);
  }

  public ProcessSupervisor(
      String imageName, ProcessBuilder processBuilder, HealthCheck healthCheck) {
    this.imageName = imageName;
    this.processBuilder = processBuilder;
    this.healthCheck = healthCheck;
    this.supervisorThread = AgentThreadFactory.newAgentThread(PROCESS_SUPERVISOR, this::mainLoop);
    this.supervisorThread.start();
  }

  private void mainLoop() {
    try {
      while (!stopping) {
        try {
          long delayMillis = nextCheckMillis - System.currentTimeMillis();
          if (delayMillis > 0) {
            Thread.sleep(delayMillis);
          }
          currentHealth = healthCheck.run(currentHealth);
          logHealthCheckChange(currentHealth);
          if (currentHealth == READY_TO_START) {
            startProcessAndWait();
          }
        } catch (InterruptedException e) {
          currentHealth = INTERRUPTED;
        } catch (Throwable e) {
          if (failuresInARow++ < QUIET_AFTER_FAILURES) {
            log.warn("Exception starting process: [{}]", imageName, e);
          } else {
            log.debug("Exception starting process: [{}]", imageName, e);
          }
          currentHealth = FAULTED;
        }
        scheduleNextHealthCheck();
      }
    } finally {
      stopProcess();
    }
  }

  private void logHealthCheckChange(Health result) {
    Health previous = lastCheckResult;
    lastCheckResult = result;
    if (result == previous) {
      return;
    }
    if (result == HEALTHY) {
      // Health checks only run while this supervisor has no running process of its own,
      // so a healthy result means another process (usually another worker) is serving it.
      failuresInARow = 0;
      log.info(
          "Process [{}] is already running and was not started by this process (pid {}); using it",
          imageName,
          PidHelper.getPid());
    } else if (result == READY_TO_START && previous == HEALTHY) {
      logLifecycle(
          "Process [{}] that this process (pid {}) was using is no longer available",
          imageName,
          PidHelper.getPid());
    }
  }

  private void scheduleNextHealthCheck() {
    long now = System.currentTimeMillis();
    if (currentHealth == HEALTHY) {
      nextCheckMillis = now + HEALTHY_DELAY_MILLIS;
    } else if (currentHealth == FAULTED) {
      nextCheckMillis = now + faultBackoff.recordFault();
    } else { // interrupted
      nextCheckMillis = Long.max(nextCheckMillis, now + 100);
    }
  }

  private void startProcessAndWait() throws Exception {
    if (currentProcess == null) {
      log.debug("Starting process: [{}]", imageName);
      try (TraceScope ignored = AgentTracer.get().muteTracing()) {
        currentProcess = processBuilder.start();
      }
      currentHealth = HEALTHY;
      logLifecycle(
          "Started process [{}] with pid {} from this process (pid {})",
          imageName,
          pidOrUnknown(currentProcess),
          PidHelper.getPid());
    }

    long startedMillis = System.currentTimeMillis();
    String childPid = pidOrUnknown(currentProcess);

    // Block until the process exits
    int code = currentProcess.waitFor();
    currentHealth = code == 0 ? INTERRUPTED : FAULTED;

    long ranMillis = System.currentTimeMillis() - startedMillis;
    faultBackoff.recordUptime(ranMillis);
    if (ranMillis >= STABLE_RUN_MILLIS) {
      failuresInARow = 0;
    }
    logLifecycle(
        "Process [{}] with pid {} exited with code {} after {} ms",
        imageName,
        childPid,
        code,
        ranMillis);
    if (code != 0 && ++failuresInARow == QUIET_AFTER_FAILURES) {
      log.warn(
          "Process [{}] has exited with an error {} times in a row; "
              + "further starts and exits are logged at debug level until it stays up for {} seconds",
          imageName,
          failuresInARow,
          MILLISECONDS.toSeconds(STABLE_RUN_MILLIS));
    }

    // Process is dead, no longer needs to be tracked
    currentProcess = null;
  }

  private void stopProcess() {
    if (currentProcess != null) {
      String childPid = pidOrUnknown(currentProcess);
      log.info("Stopping process [{}] with pid {}", imageName, childPid);
      boolean stopped = false;
      try {
        currentProcess.destroy();
        stopped = currentProcess.waitFor(STOP_WAIT_MILLIS, MILLISECONDS);
        if (!stopped) {
          currentProcess.destroyForcibly();
          stopped = currentProcess.waitFor(STOP_WAIT_MILLIS, MILLISECONDS);
        }
      } catch (InterruptedException e) {
        currentProcess.destroyForcibly();
        Thread.currentThread().interrupt();
      }
      if (stopped) {
        log.info("Stopped process [{}] with pid {}", imageName, childPid);
      } else {
        log.warn(
            "Process [{}] with pid {} was still running after the stop request",
            imageName,
            childPid);
      }
      currentProcess = null;
    } else {
      log.debug("No process [{}] started by this process to stop", imageName);
    }
  }

  private void logLifecycle(String format, Object... arguments) {
    if (failuresInARow < QUIET_AFTER_FAILURES) {
      log.info(format, arguments);
    } else {
      log.debug(format, arguments);
    }
  }

  private static String pidOrUnknown(Process process) {
    String pid = PidHelper.getPid(process);
    return pid.isEmpty() ? "unknown" : pid;
  }

  @Override
  public void close() {
    stopping = true;
    supervisorThread.interrupt();
    try {
      supervisorThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (Throwable ignored) {
    }
    if (supervisorThread.isAlive()) {
      log.warn(
          "Supervisor for process [{}] did not finish stopping within {} ms; "
              + "the process may be left running",
          imageName,
          THREAD_JOIN_TIMOUT_MS);
    }
  }

  /**
   * Counts faults in a row and spaces out the retries that follow them. Faults are forgotten only
   * once a process has stayed up for {@link #STABLE_RUN_MILLIS}, so an agent that keeps exiting
   * soon after it starts backs off instead of restarting every few seconds. Retries never stop.
   */
  static final class FaultBackoff {
    private static final int MAX_DOUBLINGS = 5;

    private int faults;

    /** Records a fault and returns how long to wait before the next health check. */
    long recordFault() {
      faults++;
      int doublings = Math.min(faults - 1, MAX_DOUBLINGS);
      return Math.min(FAULTED_DELAY_MILLIS << doublings, MAX_FAULTED_DELAY_MILLIS);
    }

    /** Forgets earlier faults once a process has been up for a stable period. */
    void recordUptime(long upMillis) {
      if (upMillis >= STABLE_RUN_MILLIS) {
        faults = 0;
      }
    }
  }

  @VisibleForTesting
  Process getCurrentProcess() {
    return currentProcess;
  }

  @VisibleForTesting
  Thread getSupervisorThread() {
    return supervisorThread;
  }
}
