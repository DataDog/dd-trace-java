package datadog.trace.logging.simplelogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.EnvironmentVariables;
import datadog.environment.EnvironmentVariables.EnvironmentVariablesProvider;
import datadog.trace.logging.PrintStreamWrapper;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class AasPerProcessLogFileTest {
  private static final String PID = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];

  private final Map<String, String> environment = new HashMap<>();
  private EnvironmentVariablesProvider originalEnvironment;

  @TempDir File logDirectory;

  @BeforeEach
  void useTestEnvironment() {
    originalEnvironment = EnvironmentVariables.provider;
    EnvironmentVariables.provider =
        new EnvironmentVariablesProvider() {
          @Override
          public String get(String name) {
            return environment.get(name);
          }

          @Override
          public Map<String, String> getAll() {
            return environment;
          }
        };
  }

  @AfterEach
  void restoreEnvironment() {
    EnvironmentVariables.provider = originalEnvironment;
  }

  @TableTest({
    "scenario            | azureAppServices | perProcessFile",
    "enabled with 1      | 1                | true          ",
    "enabled with true   | true             | true          ",
    "enabled with TRUE   | TRUE             | true          ",
    "disabled with false | false            | false         ",
    "not set             |                  | false         "
  })
  void writesThisJvmsLogToItsOwnFileOnlyInAzureAppService(
      String azureAppServices, boolean perProcessFile) throws Exception {
    environment.put("DD_AZURE_APP_SERVICES", azureAppServices);
    environment.put("DD_TRACE_LOGFILE_RETENTION_DAYS", "0");
    File configuredFile = new File(logDirectory, "dd-java-tracer.log");

    PrintStream printStream = SLCompatSettings.getPrintStream(configuredFile.getPath());
    printStream.print("a log line");
    printStream.close();

    File expectedFile =
        perProcessFile ? new File(logDirectory, "dd-java-tracer-" + PID + ".log") : configuredFile;
    assertEquals(fileNames(expectedFile.getName()), fileNames(logDirectory.list()));
    assertEquals("a log line", contents(expectedFile));
  }

  @Test
  void fallsBackToStandardErrorWhenThePerProcessLogFileCannotBeOpened() throws Exception {
    environment.put("DD_AZURE_APP_SERVICES", "true");
    File notADirectory = new File(logDirectory, "not-a-directory");
    assertTrue(notADirectory.createNewFile());

    PrintStream printStream =
        SLCompatSettings.getPrintStream(new File(notADirectory, "dd-java-tracer.log").getPath());
    waitForCleanup();

    assertSame(System.err, ((PrintStreamWrapper) printStream).getOriginalPrintStream());
  }

  @TableTest({
    "scenario          | configuredName     | perProcessName          ",
    "with extension    | dd-java-tracer.log | dd-java-tracer-{pid}.log",
    "without extension | dd-java-tracer     | dd-java-tracer-{pid}    ",
    "only an extension | .log               | .log-{pid}              "
  })
  void namesThePerProcessFileAfterTheConfiguredFileAndThePid(
      String configuredName, String perProcessName) throws IOException {
    environment.put("DD_TRACE_LOGFILE_RETENTION_DAYS", "0");

    try (AasPerProcessLogFile.RollingFileOutputStream out =
        AasPerProcessLogFile.open(new File(logDirectory, configuredName))) {
      assertEquals(
          new File(logDirectory, perProcessName.replace("{pid}", PID)).getAbsoluteFile(),
          out.currentFile().getAbsoluteFile());
    }
  }

  @Test
  void rollsOverToNumberedFilesAtTheConfiguredSize() throws IOException {
    environment.put("DD_MAX_LOGFILE_SIZE", "10");
    environment.put("DD_TRACE_LOGFILE_RETENTION_DAYS", "0");

    try (AasPerProcessLogFile.RollingFileOutputStream out =
        AasPerProcessLogFile.open(new File(logDirectory, "tracer.log"))) {
      // a write larger than the limit still goes to the empty file instead of leaving it empty
      out.write(bytes("0123456789ABC"));
      out.write(bytes("1234"));
      out.write(bytes("567890"));
      out.write('!');
      out.flush();

      assertEquals(new File(logDirectory, "tracer-" + PID + "_002.log"), out.currentFile());
    }
    assertEquals(
        fileNames(
            "tracer-" + PID + ".log", "tracer-" + PID + "_001.log", "tracer-" + PID + "_002.log"),
        fileNames(logDirectory.list()));
    assertEquals("0123456789ABC", contents(new File(logDirectory, "tracer-" + PID + ".log")));
    assertEquals("1234567890", contents(new File(logDirectory, "tracer-" + PID + "_001.log")));
    assertEquals("!", contents(new File(logDirectory, "tracer-" + PID + "_002.log")));
  }

  @TableTest({
    "scenario         | maxLogFileSize | files",
    "limit reached    | 150            | 2    ",
    "zero disables    | 0              | 1    ",
    "invalid: default | not-a-number   | 1    ",
    "unset: default   |                | 1    "
  })
  void rollsOverOnlyWhenAPositiveSizeIsConfigured(String maxLogFileSize, int files)
      throws IOException {
    environment.put("DD_MAX_LOGFILE_SIZE", maxLogFileSize);
    environment.put("DD_TRACE_LOGFILE_RETENTION_DAYS", "0");

    try (AasPerProcessLogFile.RollingFileOutputStream out =
        AasPerProcessLogFile.open(new File(logDirectory, "tracer.log"))) {
      out.write(new byte[100]);
      out.write(new byte[100]);
    }
    assertEquals(files, logDirectory.list().length);
  }

  @Test
  void appendsToAnExistingFileAndCountsItTowardsTheSize() throws IOException {
    File existing = new File(logDirectory, "tracer-" + PID + ".log");
    Files.write(existing.toPath(), bytes("earlier"));

    try (AasPerProcessLogFile.RollingFileOutputStream out =
        new AasPerProcessLogFile.RollingFileOutputStream(
            logDirectory, "tracer-" + PID, ".log", 10)) {
      out.write(bytes("later"));
    }

    assertEquals("earlier", contents(existing));
    assertEquals("later", contents(new File(logDirectory, "tracer-" + PID + "_001.log")));
  }

  @TableTest({
    "scenario                 | retentionDays | ageDays | deleted",
    "default keeps 32 days    |               | 20      | false  ",
    "default deletes after    |               | 40      | true   ",
    "configured keeps         | 5             | 1       | false  ",
    "configured deletes after | 5             | 10      | true   ",
    "zero disables cleanup    | 0             | 400     | false  ",
    "invalid: default keeps   | soon          | 20      | false  ",
    "invalid: default deletes | soon          | 40      | true   "
  })
  void deletesThisTracersPerProcessFilesOlderThanTheRetention(
      String retentionDays, int ageDays, boolean deleted) throws Exception {
    environment.put("DD_TRACE_LOGFILE_RETENTION_DAYS", retentionDays);
    File oldFile = oldFile("dd-java-tracer-1234.log", ageDays);

    AasPerProcessLogFile.open(new File(logDirectory, "dd-java-tracer.log")).close();
    waitForCleanup();

    assertEquals(deleted, !oldFile.exists());
  }

  @Test
  void cleanupDeletesOnlyThisTracersPerProcessFiles() throws Exception {
    File ownFile = oldFile("dd-java-tracer-1234.log", 40);
    File ownRolledFile = oldFile("dd-java-tracer-1234_001.log", 40);
    File configuredFile = oldFile("dd-java-tracer.log", 40);
    File otherTracerFile = oldFile("dotnet-tracer-managed-1234.log", 40);
    File otherExtension = oldFile("dd-java-tracer-1234.txt", 40);
    File notAPid = oldFile("dd-java-tracer-old.log", 40);

    AasPerProcessLogFile.open(new File(logDirectory, "dd-java-tracer.log")).close();
    waitForCleanup();

    assertFalse(ownFile.exists());
    assertFalse(ownRolledFile.exists());
    assertTrue(configuredFile.exists());
    assertTrue(otherTracerFile.exists());
    assertTrue(otherExtension.exists());
    assertTrue(notAPid.exists());
  }

  @Test
  void cleanupStopsQuietlyWhenAFileCannotBeDeleted() throws Exception {
    // a non-empty directory with a per-process file name cannot be deleted
    File undeletable = new File(logDirectory, "dd-java-tracer-1234.log");
    assertTrue(new File(undeletable, "child").mkdirs());
    assertTrue(undeletable.setLastModified(daysAgo(40)));

    try (AasPerProcessLogFile.RollingFileOutputStream out =
        AasPerProcessLogFile.open(new File(logDirectory, "dd-java-tracer.log"))) {
      waitForCleanup();
      out.write(bytes("still logging"));
      out.flush();

      assertTrue(undeletable.exists());
      assertEquals("still logging", contents(out.currentFile()));
    }
  }

  private File oldFile(String name, int ageDays) throws IOException {
    File file = new File(logDirectory, name);
    assertTrue(file.createNewFile());
    assertTrue(file.setLastModified(daysAgo(ageDays)));
    return file;
  }

  private static long daysAgo(int days) {
    return System.currentTimeMillis() - DAYS.toMillis(days);
  }

  /** Waits for the background cleanup that opening a per-process log file starts. */
  private static void waitForCleanup() throws InterruptedException {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if ("dd-log-file-cleanup".equals(thread.getName())) {
        thread.join(10_000);
      }
    }
  }

  private static TreeSet<String> fileNames(String... names) {
    return new TreeSet<>(Arrays.asList(names));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  private static String contents(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), UTF_8);
  }
}
