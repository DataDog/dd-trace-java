package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProcessManagerScopeDiagnosticsTest {
  private final ProcessManager manager =
      new ProcessManager() {
        @Override
        protected void setupTracesConsumer() {}
      };

  @Test
  void replacesCommandPlaceholderWithoutChangingAgentOrder() {
    ProcessBuilder builder =
        new ProcessBuilder(
            "java", "-javaagent:tracer.jar", ProcessManager.SCOPE_DIAGNOSTICS_ARGUMENT, "App");

    assertTrue(manager.replaceScopeDiagnosticsArgument(builder, "-javaagent:diagnostics.jar"));
    assertEquals(
        Arrays.asList("java", "-javaagent:tracer.jar", "-javaagent:diagnostics.jar", "App"),
        builder.command());
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
