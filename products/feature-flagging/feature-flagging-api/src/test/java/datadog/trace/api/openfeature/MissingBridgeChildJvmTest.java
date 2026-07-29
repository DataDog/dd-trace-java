package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MissingBridgeChildJvmTest {

  @Test
  void reportsClearErrorWhenRemoteConfigurationBridgeIsMissing() throws Exception {
    final String classpath =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(path -> !path.contains("feature-flagging-bootstrap"))
            .collect(Collectors.joining(File.pathSeparator));
    final Process process =
        new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-cp",
                classpath,
                MissingBridgeChildMain.class.getName())
            .redirectErrorStream(true)
            .start();

    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("REMOTE_CONFIGURATION_ERROR="), output);
    assertTrue(output.contains("version 1.65.0 or later"), output);
  }
}
