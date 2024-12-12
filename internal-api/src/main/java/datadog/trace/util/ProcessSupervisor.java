package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROCESS_SUPERVISOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.ProcessSupervisor.Health.FAULTED;
import static datadog.trace.util.ProcessSupervisor.Health.HEALTHY;
import static datadog.trace.util.ProcessSupervisor.Health.INTERRUPTED;
import static datadog.trace.util.ProcessSupervisor.Health.READY_TO_START;

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
  private static final int MAX_FAULTS = 5;

  private final String imageName;
  private final ProcessBuilder processBuilder;
  private final HealthCheck healthCheck;
  private final Thread supervisorThread;

  private long nextCheckMillis = 0;
  private Health currentHealth = Health.NEVER_CHECKED;
  private Process currentProcess;
  private int faults;

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
        if (currentHealth == FAULTED && ++faults >= MAX_FAULTS) {
          log.warn("Failed to start process [{}] after {} attempts", imageName, faults);
          break;
        }
        try {
          long delayMillis = nextCheckMillis - System.currentTimeMillis();
          if (delayMillis > 0) {
            Thread.sleep(delayMillis);
          }
          currentHealth = healthCheck.run(currentHealth);
          if (currentHealth == READY_TO_START) {
            startProcessAndWait();
          }
        } catch (InterruptedException e) {
          currentHealth = INTERRUPTED;
        } catch (Throwable e) {
          log.warn("Exception starting process: [{}]", imageName, e);
          currentHealth = FAULTED;
        }
        scheduleNextHealthCheck();
      }
    } finally {
      stopProcess();
    }
  }

  private void scheduleNextHealthCheck() {
    long now = System.currentTimeMillis();
    if (currentHealth == HEALTHY) {
      nextCheckMillis = now + HEALTHY_DELAY_MILLIS;
    } else if (currentHealth == FAULTED) {
      nextCheckMillis = now + FAULTED_DELAY_MILLIS;
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
      faults = 0;
    }

    // Block until the process exits
    int code = currentProcess.waitFor();
    log.debug("Process [{}] has exited with code {}", imageName, code);
    currentHealth = code == 0 ? INTERRUPTED : FAULTED;

    // Process is dead, no longer needs to be tracked
    currentProcess = null;
  }

  private void stopProcess() {
    if (currentProcess != null) {
      log.debug("Stopping process: [{}]", imageName);
      currentProcess.destroy();
      if (currentProcess.isAlive()) {
        currentProcess.destroyForcibly();
      }
      currentProcess = null;
    }
  }

  @Override
  public void close() {
    stopping = true;
    supervisorThread.interrupt();
    try {
      supervisorThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (Throwable ignored) {
    }
  }

  // Package reachable for testing
  Process getCurrentProcess() {
    return currentProcess;
  }
}
