package com.datadog.crashtracking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class ScriptInitializerTest {
  private Path tempDir;

  @BeforeEach
  void setup() throws Exception {
    tempDir = Files.createTempDirectory("dd-test-");
  }

  @AfterEach
  void teardown() throws Exception {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    Files.deleteIfExists(tempDir);
  }

  @Test
  void testSanity() {
    assertDoesNotThrow(() -> ScriptInitializer.initialize(null, null));
    assertDoesNotThrow(
        () -> ScriptInitializer.initialize(tempDir.resolve("dummy.sh").toString(), null));
    assertDoesNotThrow(() -> ScriptInitializer.initialize(null, "hs_err.log"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dd_crash_uploader.sh", "dd_crash_uploader.bat"})
  void testInitializationSuccess(String target) throws IOException {
    Path file = tempDir.resolve(target);
    String hsErrFile = "/tmp/hs_err.log";
    ScriptInitializer.initialize(file.toString(), hsErrFile);
    assertTrue(Files.exists(file), "File " + file + " should have been created");
    List<String> lines = Files.readAllLines(file);
    assertFalse(lines.isEmpty(), "File " + file + " is expected to be non-empty");
    // sanity to check the crash log file was properly replaced in the script
    assertTrue(lines.stream().anyMatch(l -> l.contains(hsErrFile)));
  }

  @Test
  void testNoInitialization() throws IOException {
    // the initializer needs a particular script name to kick-in
    Path file = tempDir.resolve("some_other_script.sh");
    String hsErrFile = "/tmp/hs_err.log";
    ScriptInitializer.initialize(file.toString(), hsErrFile);
    assertFalse(Files.exists(file), "File " + file + " should not have been created");
  }

  @Test
  void testInitializationExisting() throws IOException {
    Path file = tempDir.resolve("dd_crash_uploader.sh");
    Files.createFile(file);
    ScriptInitializer.initialize(file.toString(), "/tmp/hs_err.log");
    assertTrue(Files.exists(file), "File " + file + " should not have been removed");
    assertTrue(
        Files.readAllLines(file).isEmpty(),
        "File " + file + " content should not have been modified");
  }

  @Test
  void testNoErrFileSpec() throws IOException {
    Path file = tempDir.resolve("dd_crash_uploader.sh");
    ScriptInitializer.initialize(file.toString(), "");
    assertTrue(Files.exists(file), "File " + file + " should have been created");
    // sanity to check the crash log file was properly replaced in the script
    List<String> lines = Files.readAllLines(file);
    assertFalse(lines.isEmpty(), "File " + file + " is expected to be non-empty");
    // sanity to check the crash log file was properly replaced in the script
    assertTrue(lines.stream().anyMatch(l -> l.contains("hs_err")));
  }

  @Test
  void testInvalidFolder() throws IOException {
    Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-x------"));
    Path file = tempDir.resolve("dd_crash_uploader.sh");
    assertThrows(
        IOException.class, () -> ScriptInitializer.initialize(file.toString(), "/tmp/hs_err.log"));
  }
}
