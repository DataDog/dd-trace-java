package datadog.trace.util;

import static datadog.trace.util.ProcessSupervisor.Health.FAULTED;
import static datadog.trace.util.ProcessSupervisor.Health.HEALTHY;
import static datadog.trace.util.ProcessSupervisor.Health.READY_TO_START;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.trace.test.util.PollingConditions;
import datadog.trace.test.util.PortableCommand;
import datadog.trace.util.ProcessSupervisor.HealthCheck;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

// This test looks at the private "currentProcess" variable because the alternative
// would be calling "ps -e" repeatedly
class ProcessSupervisorTest {

  private final Logger supervisorLogger = (Logger) LoggerFactory.getLogger(ProcessSupervisor.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    supervisorLogger.addAppender(logs);
  }

  @AfterEach
  void stopCapturingLogs() {
    supervisorLogger.detachAppender(logs);
  }

  private ProcessBuilder createProcessBuilder() {
    // Creates a process that never returns on its own
    return new ProcessBuilder(PortableCommand.runForever());
  }

  @Test
  void processKilledWhenSupervisorClosed() throws InterruptedException {
    ProcessBuilder processBuilder = createProcessBuilder();
    PollingConditions conditions = new PollingConditions(11);

    ProcessSupervisor processSupervisor = new ProcessSupervisor("test", processBuilder);

    conditions.eventually(
        () -> {
          Process process = processSupervisor.getCurrentProcess();
          assertNotNull(process);
          assertTrue(process.isAlive());
        });

    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit
    // code
    Thread.sleep(1000);
    // code coverage: make sure that the supervisor thread loops around once
    processSupervisor.getSupervisorThread().interrupt();
    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit
    // code
    Thread.sleep(1000);
    Process oldProcess = processSupervisor.getCurrentProcess();
    processSupervisor.close();

    assertNotNull(oldProcess);
    conditions.eventually(
        () -> {
          assertFalse(oldProcess.isAlive());
          Process process = processSupervisor.getCurrentProcess();
          assertTrue(process == null || !process.isAlive());
        });
  }

  @Test
  void faultBackoffDoublesUpToSixtySecondsAndNeverStops() {
    ProcessSupervisor.FaultBackoff backoff = new ProcessSupervisor.FaultBackoff();

    assertEquals(2_000, backoff.recordFault());
    assertEquals(4_000, backoff.recordFault());
    assertEquals(8_000, backoff.recordFault());
    assertEquals(16_000, backoff.recordFault());
    assertEquals(32_000, backoff.recordFault());
    assertEquals(60_000, backoff.recordFault());
    for (int i = 0; i < 1_000; i++) {
      assertEquals(60_000, backoff.recordFault());
    }
  }

  @Test
  void faultBackoffResetsOnlyAfterStableUptime() {
    ProcessSupervisor.FaultBackoff backoff = new ProcessSupervisor.FaultBackoff();
    backoff.recordFault();
    backoff.recordFault();

    // a start that exits again quickly keeps the earlier faults
    backoff.recordUptime(59_999);
    assertEquals(8_000, backoff.recordFault());

    // staying up for the stable period forgets them
    backoff.recordUptime(60_000);
    assertEquals(2_000, backoff.recordFault());
  }

  @Test
  void processRespawnsWhenKilled() throws InterruptedException {
    ProcessBuilder processBuilder = createProcessBuilder();
    PollingConditions conditions = new PollingConditions(11);

    ProcessSupervisor processSupervisor = new ProcessSupervisor("test", processBuilder);

    conditions.eventually(
        () -> {
          Process process = processSupervisor.getCurrentProcess();
          assertNotNull(process);
          assertTrue(process.isAlive());
        });

    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit
    // code
    Thread.sleep(1000);
    Process firstProcess = processSupervisor.getCurrentProcess();
    firstProcess.destroyForcibly();

    assertNotNull(firstProcess);
    conditions.eventually(
        () -> {
          assertFalse(firstProcess.isAlive());
          Process process = processSupervisor.getCurrentProcess();
          assertNotNull(process);
          assertNotEquals(firstProcess, process);
          assertTrue(process.isAlive());
        });

    // code coverage: give the supervisor thread a reasonable chance to start waiting for the exit
    // code
    Thread.sleep(1000);
    Process secondProcess = processSupervisor.getCurrentProcess();
    processSupervisor.close();

    assertNotNull(secondProcess);
    conditions.eventually(
        () -> {
          assertFalse(secondProcess.isAlive());
          Process process = processSupervisor.getCurrentProcess();
          assertTrue(process == null || !process.isAlive());
        });
  }

  @Test
  void restartsItsOwnProcessAfterThreeFailedHealthChecksInARow() throws IOException {
    List<FakeProcess> started = new CopyOnWriteArrayList<>();
    AtomicInteger checksWhileRunning = new AtomicInteger();
    HealthCheck failsWhileRunning =
        previous -> {
          if (previous == HEALTHY) {
            checksWhileRunning.incrementAndGet();
            return FAULTED;
          }
          return READY_TO_START;
        };

    try (ProcessSupervisor ignored =
        new ProcessSupervisor("test", startsFakeProcesses(started, true), failsWhileRunning)) {
      new PollingConditions(5)
          .eventually(
              () -> {
                assertEquals(1, started.size());
                assertFalse(started.get(0).isAlive());
              });
      // the replacement only starts after a fault backoff of a few seconds
      assertEquals(3, checksWhileRunning.get());
      assertEquals(1, countLogs(Level.INFO, "failed a health check"));
      assertEquals(1, countLogs(Level.WARN, "failed 3 health checks in a row; restarting it"));

      // a replacement is started (it keeps failing its health checks, so it is restarted too)
      new PollingConditions(5).eventually(() -> assertTrue(started.size() >= 2));
    }
  }

  @Test
  void keepsItsOwnProcessWhenHealthChecksRecoverBeforeThreeFailures() throws IOException {
    List<FakeProcess> started = new CopyOnWriteArrayList<>();
    AtomicInteger checksWhileRunning = new AtomicInteger();
    // fails two checks in a row, then passes one, over and over
    HealthCheck sometimesFailsWhileRunning =
        previous -> {
          if (previous == HEALTHY) {
            return checksWhileRunning.incrementAndGet() % 3 == 0 ? HEALTHY : FAULTED;
          }
          return READY_TO_START;
        };

    try (ProcessSupervisor ignored =
        new ProcessSupervisor(
            "test", startsFakeProcesses(started, true), sometimesFailsWhileRunning)) {
      new PollingConditions(5).eventually(() -> assertTrue(checksWhileRunning.get() >= 9));

      assertEquals(1, started.size());
      assertEquals(0, started.get(0).stopRequests.get());
      assertTrue(countLogs(Level.INFO, "is passing health checks again") >= 2);
    }
  }

  @Test
  void usesAProcessItDidNotStartAndStartsItsOwnWhenThatOneGoesAway() throws IOException {
    List<FakeProcess> started = new CopyOnWriteArrayList<>();
    AtomicBoolean otherProcessRunning = new AtomicBoolean(true);
    HealthCheck answersWhileAnyProcessRuns =
        previous -> otherProcessRunning.get() || !started.isEmpty() ? HEALTHY : READY_TO_START;

    try (ProcessSupervisor ignored =
        new ProcessSupervisor(
            "test", startsFakeProcesses(started, true), answersWhileAnyProcessRuns)) {
      new PollingConditions(5)
          .eventually(
              () ->
                  assertEquals(
                      1,
                      countLogs(
                          Level.INFO, "is already running and was not started by this process")));
      assertTrue(started.isEmpty());

      otherProcessRunning.set(false);

      // the next check runs once the ten second healthy interval has passed
      new PollingConditions(15)
          .eventually(() -> assertEquals(1, countLogs(Level.INFO, "Started process [test]")));
      assertEquals(1, started.size());
      assertEquals(1, countLogs(Level.INFO, "was using is no longer available"));
    }
  }

  @Test
  void keepsTrackingItsProcessWhenItDoesNotStopInsteadOfStartingASecondCopy() throws IOException {
    List<FakeProcess> started = new CopyOnWriteArrayList<>();
    AtomicInteger checksWhileRunning = new AtomicInteger();
    HealthCheck failsWhileRunning =
        previous -> {
          if (previous == HEALTHY) {
            checksWhileRunning.incrementAndGet();
            return FAULTED;
          }
          return READY_TO_START;
        };

    try (ProcessSupervisor ignored =
        new ProcessSupervisor("test", startsFakeProcesses(started, false), failsWhileRunning)) {
      // after the failed restart and a fault backoff, it supervises the same process again
      new PollingConditions(10).eventually(() -> assertTrue(checksWhileRunning.get() > 3));

      assertEquals(1, started.size());
      assertTrue(started.get(0).isAlive());
      assertTrue(started.get(0).stopRequests.get() >= 2, "asks to stop, then forcibly");
      assertTrue(countLogs(Level.WARN, "was still running after the stop request") >= 1);
    }
  }

  @Test
  void retriesAfterTheProcessFailsToStart() throws IOException {
    List<FakeProcess> started = new CopyOnWriteArrayList<>();
    ProcessBuilder processBuilder = mock(ProcessBuilder.class);
    when(processBuilder.start())
        .thenThrow(new IOException("cannot start"))
        .thenAnswer(invocation -> startFakeProcess(started, true));

    try (ProcessSupervisor ignored = new ProcessSupervisor("test", processBuilder)) {
      new PollingConditions(5).eventually(() -> assertEquals(1, started.size()));

      verify(processBuilder, times(2)).start();
      assertEquals(1, countLogs(Level.WARN, "Exception starting process: [test]"));
    }
  }

  @Test
  void logsTheExitCodeAndStartsAgainAfterTheProcessExits() throws IOException {
    List<FakeProcess> started = new CopyOnWriteArrayList<>();

    try (ProcessSupervisor ignored =
        new ProcessSupervisor("test", startsFakeProcesses(started, true))) {
      new PollingConditions(5).eventually(() -> assertEquals(1, started.size()));
      started.get(0).exit(0);

      new PollingConditions(5).eventually(() -> assertEquals(2, started.size()));
      assertEquals(1, countLogs(Level.INFO, "exited with code 0 after"));
    }
  }

  private static ProcessBuilder startsFakeProcesses(
      List<FakeProcess> started, boolean stopsWhenAsked) throws IOException {
    ProcessBuilder processBuilder = mock(ProcessBuilder.class);
    when(processBuilder.start())
        .thenAnswer(invocation -> startFakeProcess(started, stopsWhenAsked));
    return processBuilder;
  }

  private static FakeProcess startFakeProcess(List<FakeProcess> started, boolean stopsWhenAsked) {
    FakeProcess process = new FakeProcess(stopsWhenAsked);
    started.add(process);
    return process;
  }

  private int countLogs(Level level, String text) {
    List<ILoggingEvent> events;
    synchronized (logs) {
      events = new ArrayList<>(logs.list);
    }
    int count = 0;
    for (ILoggingEvent event : events) {
      if (event.getLevel() == level && event.getFormattedMessage().contains(text)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Stands in for a child process. Each timed wait returns within {@link #WAIT_MILLIS}, so the
   * supervisor's ten second interval between health checks of a running process passes quickly.
   */
  private static final class FakeProcess extends Process {
    private static final long WAIT_MILLIS = 20;

    private final CountDownLatch exited = new CountDownLatch(1);
    private final boolean stopsWhenAsked;
    private volatile int exitCode;
    final AtomicInteger stopRequests = new AtomicInteger();

    FakeProcess(boolean stopsWhenAsked) {
      this.stopsWhenAsked = stopsWhenAsked;
    }

    void exit(int code) {
      exitCode = code;
      exited.countDown();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      return exited.await(Math.min(unit.toMillis(timeout), WAIT_MILLIS), MILLISECONDS);
    }

    @Override
    public int waitFor() throws InterruptedException {
      exited.await();
      return exitCode;
    }

    @Override
    public int exitValue() {
      if (isAlive()) {
        throw new IllegalThreadStateException("still running");
      }
      return exitCode;
    }

    @Override
    public boolean isAlive() {
      return exited.getCount() > 0;
    }

    @Override
    public void destroy() {
      stopRequests.incrementAndGet();
      if (stopsWhenAsked) {
        exit(143);
      }
    }

    @Override
    public Process destroyForcibly() {
      destroy();
      return this;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }
  }
}
