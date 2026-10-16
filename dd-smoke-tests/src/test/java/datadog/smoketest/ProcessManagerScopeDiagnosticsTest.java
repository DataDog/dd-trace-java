package datadog.smoketest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessManagerScopeDiagnosticsTest {
  private final ProcessManager manager =
      new ProcessManager() {
        @Override
        protected void setupTracesConsumer() {}
      };

  @Test
  void replacesCommandPlaceholderWithoutChangingAgentOrder() {
    String argument = "-javaagent:/work space/diagnostics.jar=/report space/control";
    ProcessBuilder builder =
        new ProcessBuilder(
            "java", "-javaagent:tracer.jar", ProcessManager.SCOPE_DIAGNOSTICS_ARGUMENT, "App");

    assertTrue(manager.replaceScopeDiagnosticsArgument(builder, argument));
    assertEquals(
        Arrays.asList("java", "-javaagent:tracer.jar", argument, "App"), builder.command());
  }

  @Test
  void replacesEnvironmentPlaceholder() {
    ProcessBuilder builder = new ProcessBuilder("launcher");
    builder
        .environment()
        .put("JAVA_OPTS", "-javaagent:tracer.jar " + ProcessManager.SCOPE_DIAGNOSTICS_ARGUMENT);

    assertTrue(manager.replaceScopeDiagnosticsArgument(builder, "-javaagent:diagnostics.jar"));
    assertEquals(
        "-javaagent:tracer.jar -javaagent:diagnostics.jar", builder.environment().get("JAVA_OPTS"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"JAVA_OPTS", "CATALINA_OPTS"})
  @EnabledOnOs({LINUX, MAC})
  void preservesPathsWithSpacesWhenLauncherEvaluatesOptions(String variable) throws Exception {
    String argument = "-javaagent:/work space/diagnostics.jar=/report space/control";
    ProcessBuilder builder =
        new ProcessBuilder(
            "/bin/sh", "-c", "eval \"set -- $" + variable + "\"; printf '%s\\n' \"$@\"");
    builder
        .environment()
        .put(
            variable,
            "-javaagent:/work\\ space/tracer.jar "
                + ProcessManager.SCOPE_DIAGNOSTICS_ARGUMENT
                + " -Dtest.option=after");

    assertTrue(manager.replaceScopeDiagnosticsArgument(builder, argument));
    Process process = builder.start();
    try {
      assertTrue(process.waitFor(10, SECONDS));
      assertEquals(0, process.exitValue());
      try (BufferedReader output =
          new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
        List<String> arguments = output.lines().collect(toList());
        assertEquals(
            Arrays.asList("-javaagent:/work space/tracer.jar", argument, "-Dtest.option=after"),
            arguments);
      }
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void removesCommandPlaceholderForDocumentedOptOut() {
    ProcessBuilder builder =
        new ProcessBuilder("java", ProcessManager.SCOPE_DIAGNOSTICS_ARGUMENT, "App");

    assertTrue(manager.replaceScopeDiagnosticsArgument(builder, ""));
    assertEquals(Arrays.asList("java", "App"), builder.command());
  }

  @Test
  void reportsMissingPlaceholder() {
    ProcessBuilder builder = new ProcessBuilder("java", "App");

    assertFalse(manager.replaceScopeDiagnosticsArgument(builder, "-javaagent:diagnostics.jar"));
    assertEquals(Arrays.asList("java", "App"), builder.command());
  }
}
