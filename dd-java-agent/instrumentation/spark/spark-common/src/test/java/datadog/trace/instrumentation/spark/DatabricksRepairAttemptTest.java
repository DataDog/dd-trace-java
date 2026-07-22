package datadog.trace.instrumentation.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DatabricksRepairAttemptTest {

  private static String loadResource(String path) {
    try (InputStream stream = DatabricksRepairAttemptTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("missing test resource " + path);
      }
      byte[] bytes = stream.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void extractsAttemptZeroFromOriginalRunPayload() {
    Properties properties = new Properties();
    properties.setProperty(
        "unity.scope.data", loadResource("/databricks/unity-scope-data-original.txt"));

    assertEquals(0, AbstractDatadogSparkListener.getDatabricksJobRunAttempt(properties));
  }

  @Test
  void extractsAttemptOneFromRepairedRunPayload() {
    Properties properties = new Properties();
    properties.setProperty(
        "unity.scope.data", loadResource("/databricks/unity-scope-data-repaired.txt"));

    assertEquals(1, AbstractDatadogSparkListener.getDatabricksJobRunAttempt(properties));
  }

  @Test
  void fallsBackToZeroWhenPropertyIsMissing() {
    Properties properties = new Properties();

    assertEquals(0, AbstractDatadogSparkListener.getDatabricksJobRunAttempt(properties));
  }

  @Test
  void fallsBackToZeroWhenPayloadIsNotValidBase64() {
    Properties properties = new Properties();
    properties.setProperty("unity.scope.data", "not-valid-base64-@@@");

    assertEquals(0, AbstractDatadogSparkListener.getDatabricksJobRunAttempt(properties));
  }

  @Test
  void fallsBackToZeroWhenKeyIsAbsent() {
    // Base64 of a serialized map that never mentions jobRunAttemptNum at all.
    Properties properties = new Properties();
    properties.setProperty(
        "unity.scope.data",
        java.util.Base64.getEncoder()
            .encodeToString("no attempt info in here".getBytes(StandardCharsets.UTF_8)));

    assertEquals(0, AbstractDatadogSparkListener.getDatabricksJobRunAttempt(properties));
  }

  @Test
  void fallsBackToZeroWhenValueIsABackreferenceRatherThanAString() {
    // If the attempt value string was already written earlier in the serialized stream, Java
    // serialization emits a TC_REFERENCE (0x71) back-pointer instead of repeating the string. We
    // don't resolve backreferences, so this must safely fall back to attempt 0 rather than risk
    // scanning forward and matching an unrelated byte as the value.
    byte[] key = "jobRunAttemptNum".getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[key.length + 5];
    System.arraycopy(key, 0, payload, 0, key.length);
    payload[key.length] = 0x71; // TC_REFERENCE
    payload[key.length + 1] = 0x00;
    payload[key.length + 2] = 0x00;
    payload[key.length + 3] = 0x00;
    payload[key.length + 4] = 0x01;

    Properties properties = new Properties();
    properties.setProperty(
        "unity.scope.data", java.util.Base64.getEncoder().encodeToString(payload));

    assertEquals(0, AbstractDatadogSparkListener.getDatabricksJobRunAttempt(properties));
  }

  @Test
  void computesDistinctTraceIdsForOriginalAndRepairedAttemptOfTheSameRun() {
    // A repaired run reuses the same jobId/parentRunId as the original (that's the whole reason
    // spans collided before this fix); it gets a fresh taskRunId per attempt, but that alone isn't
    // enough since the job span (the trace root) is keyed on jobId+parentRunId, not taskRunId.
    String jobId = "1234";
    String parentRunId = "5678";

    DatabricksParentContext original = new DatabricksParentContext(jobId, parentRunId, "9012", 0);
    DatabricksParentContext repaired = new DatabricksParentContext(jobId, parentRunId, "3456", 1);

    assertEquals(DDTraceId.from("8944764253919609482"), original.getTraceId());
    assertNotEquals(original.getTraceId(), repaired.getTraceId());
    assertNotEquals(DDSpanId.ZERO, repaired.getSpanId());
  }

  @Test
  void attemptZeroReproducesTheOriginalNonRepairAwareTraceId() {
    // Backward compatibility: runs that were never repaired must keep the exact trace id they
    // always had, since attempt 0 is the overwhelmingly common case.
    DatabricksParentContext withDefaultAttempt =
        new DatabricksParentContext("1234", "5678", "9012", 0);

    assertEquals(DDTraceId.from("8944764253919609482"), withDefaultAttempt.getTraceId());
    assertEquals(DDSpanId.from("15104224823446433673"), withDefaultAttempt.getSpanId());
  }
}
