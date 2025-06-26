package datadog.crashtracking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class InitializerTest {
  @ParameterizedTest
  @ValueSource(strings = {"summary_pid12345.log", "image_pid1_2345.txt"})
  void testPidFromSpecialFileName(String fileName) {
    String pid = Initializer.pidFromSpecialFileName(fileName);
    assertTrue(pid.matches("\\d+"), "PID should be a number");
    assertFalse(pid.isEmpty(), "PID should not be empty");
  }

  @ParameterizedTest
  @ValueSource(strings = {"pit_12345.log", "pid12345.log", "summary_pid_12345.log", ""})
  void testInvalidPidFromSpecialFileName(String fileName) {
    String pid = Initializer.pidFromSpecialFileName(fileName);
    assertTrue(pid == null || pid.isEmpty(), "PID should be empty for invalid file names");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "dd_oome_notifier.sh",
        "dd_oome_notifier.sh %p",
        "/tmp/dd_oome_notifier.sh",
        "on_oome.sh; /tmp/dd_oome_notifier.sh %p",
        "/tmp/dd_oome_notifier.sh %p;/tmp/another_script.sh",
        "/tmp/ddprof_root/pid_1/dd_oome_notifier.sh %p"
      })
  void testValidOomeNotifierScript(String script) {
    String expectedPath = Initializer.getScriptPathFromArg(script, "dd_oome_notifier.sh");
    assertNotNull(expectedPath, "Script path should not be null");
    assertTrue(
        expectedPath.endsWith("dd_oome_notifier.sh"),
        "Script path should end with dd_oome_notifier.sh");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "dd_oome_notifie.sh %p", "on_oome.sh"})
  void testInvalidOomeNotifierScript(String script) {
    String expectedPath = Initializer.getScriptPathFromArg(script, "dd_oome_notifier.sh");
    assertNull(expectedPath, "Script path should be null for invalid scripts");
  }
}
