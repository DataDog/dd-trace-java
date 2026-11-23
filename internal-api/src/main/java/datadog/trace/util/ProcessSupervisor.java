package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROCESS_SUPERVISOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.ProcessSupervisor.Health.FAULTED;
import static datadog.trace.util.ProcessSupervisor.Health.HEALTHY;
import static datadog.trace.util.ProcessSupervisor.Health.INTERRUPTED;
import static datadog.trace.util.ProcessSupervisor.Health.READY_TO_START;
import static datadog.trace.util.ProcessSupervisor.Health.UNHEALTHY;
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
    HEALTHY,
    /** A process is serving, but did not pass the health check. */
    UNHEALTHY
  }

  /**
   * Checks whether the supervised program is healthy. It runs in two situations:
   *
   * <ul>
   *   <li>While this supervisor has no process of its own, to decide what to do: {@link
   *       Health#READY_TO_START} starts a process, {@link Health#HEALTHY} uses a process that
   *       another one started, and {@link Health#UNHEALTHY} waits until that process has failed
   *       several checks in a row before starting a copy.
   *   <li>While a process this supervisor started is running, with {@code previousHealth} {@link
   *       Health#HEALTHY}. Any other result is a failed check, and several in a row restart the
   *       process.
   * </ul>
   *
   * A supervisor given {@link #ALWAYS_READY} skips the second kind of check.
   */
  @FunctionalInterface
  public interface HealthCheck {
    Health run(Health previousHealth) throws InterruptedException;

    /** Releases what the check holds. Called once, on the supervisor thread, when it stops. */
    default void close() {}
  }

  /** Starts a process whenever none is running, and never checks a running one. */
  public static final HealthCheck ALWAYS_READY = health -> READY_TO_START;

  private static final Logger log = LoggerFactory.getLogger(ProcessSupervisor.class);

  private static final long HEALTHY_DELAY_MILLIS = 10_000;
  private static final long FAULTED_DELAY_MILLIS = 2_000;
  private static final long MAX_FAULTED_DELAY_MILLIS = 60_000;

  /**
   * Lifecycle events are logged at info level. A process that keeps failing quickly would flood the
   * log, so after this many failures in a row the details go to debug level until the process stays
   * up for {@link #STABLE_RUN_MILLIS}.
   */
  private static final int QUIET_AFTER_FAILURES = 5;

  /** How long a process must stay up, or healthy, before earlier failures are forgotten. */
  private static final long STABLE_RUN_MILLIS = 60_000;

  /** How long to wait after each stop request. */
  private static final long STOP_WAIT_MILLIS = 200;

  /**
   * A process this supervisor started is restarted after failing this many health checks in a row.
   * When a process it did not start fails this many, it starts its own.
   */
  private static final int MAX_FAILED_HEALTH_CHECKS = 3;

  private final String imageName;
  private final ProcessBuilder processBuilder;
  private final HealthCheck healthCheck;
  private final Thread supervisorThread;

  private long nextCheckMillis = 0;
  private Health currentHealth = Health.NEVER_CHECKED;
  private Health lastCheckResult = Health.NEVER_CHECKED;
  private Process currentProcess;
  private long currentProcessStartedMillis;
  private int failedHealthChecks;
  private int otherProcessFailedHealthChecks;
  private long otherProcessHealthySinceMillis;
  private final FaultBackoff faultBackoff = new FaultBackoff();

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
          if (currentProcess == null) {
            currentHealth = healthCheck.run(currentHealth);
            logHealthCheckChange(currentHealth);
            recordOtherProcessCheck(currentHealth);
          }
          // A copy started next to a process that is still serving cannot take over until that one
          // exits, so it is only started once that process has failed several checks in a row.
          if (currentProcess != null
              || currentHealth == READY_TO_START
              || otherProcessFailedHealthChecks >= MAX_FAILED_HEALTH_CHECKS) {
            startProcessAndSupervise();
          }
        } catch (InterruptedException e) {
          currentHealth = INTERRUPTED;
        } catch (Throwable e) {
          logFailure("Exception supervising process: [{}]", imageName, e);
          currentHealth = FAULTED;
        }
        scheduleNextHealthCheck();
      }
    } finally {
      stopProcess();
      healthCheck.close();
    }
  }

  private void logHealthCheckChange(Health result) {
    Health previous = lastCheckResult;
    lastCheckResult = result;
    if (result == previous) {
      return;
    }
    // These checks only run while this supervisor has no running process of its own,
    // so a healthy or unhealthy result means another process (usually another worker) is serving.
    if (result == HEALTHY) {
      if (previous == UNHEALTHY) {
        log.info(
            "Process [{}] that this process (pid {}) is using is passing health checks again",
            imageName,
            PidHelper.getPid());
      } else {
        log.info(
            "Process [{}] is already running and was not started by this process (pid {}); using it",
            imageName,
            PidHelper.getPid());
      }
    } else if (result == UNHEALTHY) {
      logLifecycle(
          "Process [{}] is running but was not started by this process (pid {}) and failed a health"
              + " check; starting a new one if it fails {} checks in a row",
          imageName,
          PidHelper.getPid(),
          MAX_FAILED_HEALTH_CHECKS);
    } else if (result == READY_TO_START && (previous == HEALTHY || previous == UNHEALTHY)) {
      logLifecycle(
          "Process [{}] that this process (pid {}) was using is no longer available",
          imageName,
          PidHelper.getPid());
    }
  }

  /**
   * Tracks a process this supervisor did not start. As with a process it started, earlier failures
   * are forgotten only once it has stayed healthy for {@link #STABLE_RUN_MILLIS}, so restarts next
   * to an agent that is only sometimes healthy still back off and their logs stay quiet.
   */
  private void recordOtherProcessCheck(Health result) {
    otherProcessFailedHealthChecks = result == UNHEALTHY ? otherProcessFailedHealthChecks + 1 : 0;
    if (result != HEALTHY) {
      otherProcessHealthySinceMillis = 0;
      return;
    }
    long now = System.currentTimeMillis();
    if (otherProcessHealthySinceMillis == 0) {
      otherProcessHealthySinceMillis = now;
    }
    faultBackoff.recordUptime(now - otherProcessHealthySinceMillis);
  }

  private void scheduleNextHealthCheck() {
    long now = System.currentTimeMillis();
    if (currentHealth == HEALTHY || currentHealth == UNHEALTHY) {
      nextCheckMillis = now + HEALTHY_DELAY_MILLIS;
    } else if (currentHealth == FAULTED) {
      nextCheckMillis = now + recordFault();
    } else { // interrupted
      nextCheckMillis = Long.max(nextCheckMillis, now + 100);
    }
  }

  private void startProcessAndSupervise() throws Exception {
    if (currentProcess == null) {
      otherProcessFailedHealthChecks = 0;
      otherProcessHealthySinceMillis = 0;
      log.debug("Starting process: [{}]", imageName);
      try (TraceScope ignored = AgentTracer.get().muteTracing()) {
        currentProcess = processBuilder.start();
      }
      currentProcessStartedMillis = System.currentTimeMillis();
      failedHealthChecks = 0;
      currentHealth = HEALTHY;
      logLifecycle(
          "Started process [{}] with pid {} from this process (pid {})",
          imageName,
          pidOrUnknown(currentProcess),
          PidHelper.getPid());
    }

    String childPid = pidOrUnknown(currentProcess);

    // Keep checking on the process until it exits
    while (!currentProcess.waitFor(HEALTHY_DELAY_MILLIS, MILLISECONDS)) {
      // Time up counts only while the process passes its checks. One that is failing them, or
      // that did not stop when asked, has not stayed up in a useful way.
      if (failedHealthChecks == 0) {
        faultBackoff.recordUptime(System.currentTimeMillis() - currentProcessStartedMillis);
      }
      if (healthCheck != ALWAYS_READY && !passesHealthCheck(childPid)) {
        restartUnhealthyProcess(childPid);
        return;
      }
    }
    int code = currentProcess.exitValue();
    long ranMillis = System.currentTimeMillis() - currentProcessStartedMillis;
    boolean stayedUp = ranMillis >= STABLE_RUN_MILLIS;
    // An exit before the process stayed up is a fault whatever its code. The trace agent exits
    // with 0 when APM is disabled, for example, and restarting that every 100 ms never ends.
    boolean failed = code != 0 || !stayedUp;
    currentHealth = failed ? FAULTED : INTERRUPTED;

    faultBackoff.recordUptime(ranMillis);
    logLifecycle(
        "Process [{}] with pid {} exited with code {} after {} ms",
        imageName,
        childPid,
        code,
        ranMillis);

    // Process is dead, no longer needs to be tracked
    currentProcess = null;
  }

  /**
   * Checks on the running process this supervisor started.
   *
   * @return false once it has failed {@link #MAX_FAILED_HEALTH_CHECKS} health checks in a row
   */
  private boolean passesHealthCheck(String childPid) throws InterruptedException {
    if (healthCheck.run(HEALTHY) == HEALTHY) {
      if (failedHealthChecks > 0) {
        logLifecycle(
            "Process [{}] with pid {} is passing health checks again", imageName, childPid);
      }
      failedHealthChecks = 0;
      return true;
    }
    if (++failedHealthChecks == 1) {
      logLifecycle(
          "Process [{}] with pid {} started by this process (pid {}) failed a health check",
          imageName,
          childPid,
          PidHelper.getPid());
    }
    return failedHealthChecks < MAX_FAILED_HEALTH_CHECKS;
  }

  private void restartUnhealthyProcess(String childPid) {
    logFailure(
        "Process [{}] with pid {} failed {} health checks in a row; restarting it",
        imageName,
        childPid,
        failedHealthChecks);
    stopProcess();
    // The next health check starts a new process through the normal start path, after a backoff
    currentHealth = FAULTED;
  }

  private void stopProcess() {
    if (currentProcess != null) {
      String childPid = pidOrUnknown(currentProcess);
      logLifecycle("Stopping process [{}] with pid {}", imageName, childPid);
      // On Windows, where the AAS agents run, destroy() and destroyForcibly() both call
      // TerminateProcess: the process stops at once, without a chance to flush. Elsewhere
      // destroy() may ask the process to exit, so destroyForcibly() follows if it is still running.
      boolean interrupted = Thread.interrupted();
      boolean stopped = false;
      try {
        currentProcess.destroy();
        stopped = currentProcess.waitFor(STOP_WAIT_MILLIS, MILLISECONDS);
        if (!stopped) {
          currentProcess.destroyForcibly();
          stopped = currentProcess.waitFor(STOP_WAIT_MILLIS, MILLISECONDS);
        }
      } catch (InterruptedException e) {
        interrupted = true;
        currentProcess.destroyForcibly();
        stopped = !currentProcess.isAlive();
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      if (stopped) {
        logLifecycle("Stopped process [{}] with pid {}", imageName, childPid);
        currentProcess = null;
      } else {
        // Keep tracking it, so a restart does not start a second copy while this one runs
        logFailure(
            "Process [{}] with pid {} was still running after the stop request",
            imageName,
            childPid);
      }
    } else {
      log.debug("No process [{}] started by this process to stop", imageName);
    }
  }

  private void logLifecycle(String format, Object... arguments) {
    if (!faultBackoff.isQuiet()) {
      log.info(format, arguments);
    } else {
      log.debug(format, arguments);
    }
  }

  /** Logs a failure at warn level, or at debug level once the log has gone quiet. */
  private void logFailure(String format, Object... arguments) {
    if (!faultBackoff.isQuiet()) {
      log.warn(format, arguments);
    } else {
      log.debug(format, arguments);
    }
  }

  /**
   * Records a fault and returns how long to wait before the next health check. When the faults in a
   * row reach {@link #QUIET_AFTER_FAILURES}, says that lifecycle lines go to debug level until the
   * process stays up.
   */
  private long recordFault() {
    long delayMillis = faultBackoff.recordFault();
    if (faultBackoff.faultsInARow() == QUIET_AFTER_FAILURES) {
      log.warn(
          "Process [{}] has failed {} times in a row; "
              + "further starts and exits are logged at debug level until it stays up for {} seconds",
          imageName,
          QUIET_AFTER_FAILURES,
          MILLISECONDS.toSeconds(STABLE_RUN_MILLIS));
    }
    return delayMillis;
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
   * Counts faults in a row, spaces out the retries that follow them, and decides when lifecycle
   * logging goes quiet. Faults are forgotten only once a process has stayed up for {@link
   * #STABLE_RUN_MILLIS}, so an agent that keeps exiting soon after it starts backs off instead of
   * restarting every few seconds. Retries never stop.
   */
  @VisibleForTesting
  static final class FaultBackoff {
    private static final int MAX_DOUBLINGS = 5;

    private int faults;

    /** Records a fault and returns how long to wait before the next health check. */
    long recordFault() {
      faults++;
      int doublings = Math.min(faults - 1, MAX_DOUBLINGS);
      return Math.min(FAULTED_DELAY_MILLIS << doublings, MAX_FAULTED_DELAY_MILLIS);
    }

    int faultsInARow() {
      return faults;
    }

    /** Whether lifecycle lines go to debug level, after {@link #QUIET_AFTER_FAILURES} faults. */
    boolean isQuiet() {
      return faults >= QUIET_AFTER_FAILURES;
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
