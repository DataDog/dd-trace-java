package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProviderOnlyChildJvmTest {

  @Test
  void evaluatesCdnFlagWithoutJavaAgent() throws Exception {
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
                ProviderOnlyChildMain.class.getName())
            .redirectErrorStream(true)
            .start();

    assertTrue(process.waitFor(20, TimeUnit.SECONDS));
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("AGENT_ATTACHED=false"), output);
    assertTrue(output.contains("AGENT_GATEWAY_AVAILABLE=false"), output);
    assertTrue(output.contains("REQUESTS_BEFORE_ACTIVATION=0"), output);
    assertTrue(output.contains("VALUE=hello"), output);
  }
}
