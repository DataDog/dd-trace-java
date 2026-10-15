package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.PollingConditions;
import datadog.trace.test.util.PortableCommand;
import org.junit.jupiter.api.Test;

// This test looks at the private "currentProcess" variable because the alternative
// would be calling "ps -e" repeatedly
class ProcessSupervisorTest {

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
}
