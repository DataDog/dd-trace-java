package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RemoteConfigWithoutAgentChildJvmTest {

  @Test
  void reportsClearErrorWhenRemoteConfigurationRequiresAgent() throws Exception {
    final String classpath =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(path -> !path.contains("feature-flagging-bootstrap"))
                .filter(path -> !path.contains("feature-flagging-core"))
                .filter(path -> !path.contains("feature-flagging-http"))
                .filter(
                    path ->
                        !path.contains(
                            "feature-flagging-api"
                                + File.separator
                                + "build"
                                + File.separator
                                + "classes"
                                + File.separator
                                + "java"
                                + File.separator
                                + "main"))
                .collect(Collectors.joining(File.pathSeparator))
            + File.pathSeparator
            + System.getProperty("dd.openfeature.test.jar");
    final Process process =
        new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-cp",
                classpath,
                RemoteConfigWithoutAgentChildMain.class.getName())
            .redirectErrorStream(true)
            .start();

    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("REMOTE_CONFIGURATION_ERROR="), output);
    assertTrue(output.contains("requires dd-java-agent.jar"), output);
  }
}
