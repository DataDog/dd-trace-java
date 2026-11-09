package datadog.smoketest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;
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
  void preservesIntentionalEmptyArguments() {
    ProcessBuilder builder =
        new ProcessBuilder("java", ProcessManager.SCOPE_DIAGNOSTICS_ARGUMENT, "App", "");
    assertTrue(manager.replaceScopeDiagnosticsArgument(builder, ""));
    assertEquals(Arrays.asList("java", "App", ""), builder.command());
    assertFalse(manager.replaceScopeDiagnosticsArgument(builder, "-javaagent:diagnostics.jar"));
    assertEquals(Arrays.asList("java", "App", ""), builder.command());
  }

  @Test
  @EnabledOnOs({LINUX, MAC})
  void processLifetimeRecordingStillRequiresALiveProcess() throws Exception {
    Process exited = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
    assertTrue(exited.waitFor(10, SECONDS));
    AssertionError failure =
        assertThrows(AssertionError.class, () -> manager.assertProcessAvailable(exited));
    assertTrue(failure.getMessage().contains("is not available on test beginning"));
  }

  @Test
  @EnabledOnOs({LINUX, MAC})
  void batchApplicationCanOptIntoEarlyExit() throws Exception {
    ProcessManager batch =
        new ProcessManager() {
          @Override
          protected void setupTracesConsumer() {}

          @Override
          protected boolean expectsProcessAliveBeforeEachTest() {
            return false;
          }
        };
    Process exited = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
    assertTrue(exited.waitFor(10, SECONDS));
    assertDoesNotThrow(() -> batch.assertProcessAvailable(exited));
  }

  @Test
  void replacesLibertyOptionsInTheExplicitJar(@TempDir Path directory) throws Exception {
    Path jar = directory.resolve("copied app.jar");
    URI uri = URI.create("jar:" + jar.toUri());
    Path options;
    try (java.nio.file.FileSystem fs =
        FileSystems.newFileSystem(uri, Collections.singletonMap("create", "true"))) {
      options = fs.getPath("wlp", "usr", "servers", "defaultServer", "jvm.options");
      Files.createDirectories(options.getParent());
      Files.write(
          options,
          Arrays.asList(
              "-javaagent:tracer.jar", ProcessManager.SCOPE_DIAGNOSTICS_ARGUMENT, "-Dtest=after"));
    }
    String argument = "-javaagent:/work space/diagnostics.jar=/report space/control";
    assertTrue(manager.replaceScopeDiagnosticsArgumentInJar(jar, argument));
    try (java.nio.file.FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
      assertEquals(
          Arrays.asList("-javaagent:tracer.jar", argument, "-Dtest=after"),
          Files.readAllLines(fs.getPath("wlp", "usr", "servers", "defaultServer", "jvm.options")));
    }
    assertFalse(manager.replaceScopeDiagnosticsArgumentInJar(jar, argument));
  }

  @Test
  void reportsMissingPlaceholder() {
    ProcessBuilder builder = new ProcessBuilder("java", "App");

    assertFalse(manager.replaceScopeDiagnosticsArgument(builder, "-javaagent:diagnostics.jar"));
    assertEquals(Arrays.asList("java", "App"), builder.command());
  }
}
