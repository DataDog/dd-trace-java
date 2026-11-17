package datadog.trace.agent;

import static jvmbootstraptest.InitializationTelemetryCheck.runTestJvm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_24;

import datadog.test.SimpleAgentMock;
import datadog.trace.test.agent.decoder.DecodedSpan;
import jvmbootstraptest.InitializationTelemetryCheck.BlockAgentLoading;
import jvmbootstraptest.InitializationTelemetryCheck.BlockByteBuddy;
import jvmbootstraptest.InitializationTelemetryCheck.BlockForwarderEnvVar;
import jvmbootstraptest.InitializationTelemetryCheck.BlockForwarderExecution;
import jvmbootstraptest.InitializationTelemetryCheck.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledForJreRange;

@Timeout(30)
class InitializationTelemetryTest {

  @Test
  @DisabledForJreRange(
      min = JAVA_24,
      disabledReason = "SecurityManager is permanently disabled as of JDK 24")
  void blockAgentStartUp() throws Exception {
    // In this case, the SecurityManager blocks loading of the Premain Class,
    // so the JVM is expected to terminate with an error
    // when
    Result result = runTestJvm(BlockAgentLoading.class);

    // then
    assertNotEquals(0, result.exitCode);
    // JVM fails before premain, so no telemetry output
    assertNull(result.telemetryJson);
  }

  @Test
  void normalStartUp() throws Exception {
    // when
    Result result = runTestJvm(null);

    // then
    assertEquals(0, result.exitCode);
    assertTrue(result.telemetryJson.contains("library_entrypoint.complete"));
  }

  @Test
  void testInitialTelemetryForwarderTraceMuted() throws Exception {
    // when
    try (SimpleAgentMock agent = new SimpleAgentMock().start()) {
      Result result = runTestJvm(null, agent.getPort());

      // then
      assertEquals(0, result.exitCode);
      assertTrue(result.telemetryJson.contains("library_entrypoint.complete"));

      // Check that we have only one span related to sub-process execution,
      // and it is not initial telemetry forwarder.
      assertEquals(1, agent.getSpans().size());
      DecodedSpan span = agent.getSpans().get(0);
      assertEquals("command_execution", span.getName());
      assertEquals("echo", span.getResource());
    }
  }

  @Test
  @DisabledForJreRange(
      min = JAVA_24,
      disabledReason = "SecurityManager is permanently disabled as of JDK 24")
  void incompleteAgentStartUp() throws Exception {
    // In this case, the SecurityManager blocks a custom permission that is checked by bytebuddy
    // causing agent initialization to fail. However, we should catch the exception allowing the
    // application to run normally.
    // when
    Result result = runTestJvm(BlockByteBuddy.class);

    // then should complete successfully and catch error
    assertEquals(0, result.exitCode);
    assertFalse(result.telemetryJson.contains("library_entrypoint.complete"));
    assertTrue(result.telemetryJson.contains("error_type:"));
  }

  @Test
  @DisabledForJreRange(
      min = JAVA_24,
      disabledReason = "SecurityManager is permanently disabled as of JDK 24")
  void blockForwarderEnvVar() throws Exception {
    // In this case, the SecurityManager blocks access to the forwarder environment variable,
    // so the tracer is unable to report initialization telemetry
    // when
    Result result = runTestJvm(BlockForwarderEnvVar.class);

    // then
    assertEquals(0, result.exitCode);
    // forwarder env var unreadable, so no telemetry output
    assertNull(result.telemetryJson);
  }

  @Test
  @DisabledForJreRange(
      min = JAVA_24,
      disabledReason = "SecurityManager is permanently disabled as of JDK 24")
  void blockForwarderExecution() throws Exception {
    // In this case, the SecurityManager blocks access to process execution, so the tracer is
    // unable to invoke the forwarder executable
    // when
    Result result = runTestJvm(BlockForwarderExecution.class);

    // then
    assertEquals(0, result.exitCode);
    // forwarder execution is blocked, so no telemetry output
    assertNull(result.telemetryJson);
  }
}
