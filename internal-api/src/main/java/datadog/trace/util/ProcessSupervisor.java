package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROCESS_SUPERVISOR;

import java.io.Closeable;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts an external process and restarts the process if it dies */
public class ProcessSupervisor implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ProcessSupervisor.class);
  private static final long MIN_RESTART_INTERVAL_MS = 10 * 1000;

  private final String name;
  private final ProcessBuilder processBuilder;
  private final Thread supervisorThread;

  private long nextRestartTime = 0;
  private volatile Process currentProcess;
  private volatile boolean stopped = false;

  /**
   * @param name For logging purposes
   * @param processBuilder Builder to create the process
   */
  public ProcessSupervisor(String name, ProcessBuilder processBuilder) {
    this.name = name;
    this.processBuilder = processBuilder;
    supervisorThread = AgentThreadFactory.newAgentThread(PROCESS_SUPERVISOR, new SupervisorLoop());
    supervisorThread.start();
  }

  private class SupervisorLoop implements Runnable {
    @Override
    public void run() {
      try {
        while (!stopped) {
          try {
            if (currentProcess == null) {
              long restartDelay = nextRestartTime - System.currentTimeMillis();
              if (restartDelay > 0) {
                Thread.sleep(restartDelay);
                continue;
              }

              log.debug("Starting process: [{}]", name);
              nextRestartTime = System.currentTimeMillis() + MIN_RESTART_INTERVAL_MS;
              currentProcess = processBuilder.start();
            }

            // Block until the process exits
            int code = currentProcess.waitFor();
            log.debug("Process [{}] has exited with code {}", name, code);

            // Process is dead, no longer needs to be tracked
            currentProcess = null;
          } catch (InterruptedException ignored) {
          } catch (IOException e) {
            log.error("Exception starting process: [{}]", name, e);
          }
        }
      } finally {
        if (currentProcess != null) {
          log.debug("Stopping process [{}]", name);
          currentProcess.destroy();
        }
      }
    }
  }

  @Override
  public void close() {
    stopped = true;
    supervisorThread.interrupt();
  }

  // Package reachable for testing
  Process getCurrentProcess() {
    return currentProcess;
  }
}
