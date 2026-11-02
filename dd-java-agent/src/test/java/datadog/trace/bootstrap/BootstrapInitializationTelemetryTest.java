package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.FileNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class BootstrapInitializationTelemetryTest {
  private Capture capture;
  private BootstrapInitializationTelemetry initTelemetry;

  @BeforeEach
  void setup() {
    capture = new Capture();
    initTelemetry = new BootstrapInitializationTelemetry.JsonBased(capture);
  }

  @Test
  void testHappyPath() {
    // when
    initTelemetry.finish();
    // then
    assertJson(
        "success",
        "success",
        "Successfully configured ddtrace package",
        point("library_entrypoint.complete"));
  }

  @Test
  void testNonFatalErrorAsText() {
    // when
    initTelemetry.onError("some reason");
    initTelemetry.finish();
    // then
    assertJson(
        "error",
        "unknown",
        "some reason",
        point("library_entrypoint.error", "error_type:some reason"),
        point("library_entrypoint.complete"));
  }

  @Test
  void testNonFatalErrorAsException() {
    // when
    initTelemetry.onError(new Exception("non fatal error"));
    initTelemetry.finish();
    // then
    assertJson(
        "error",
        "internal_error",
        "non fatal error",
        point("library_entrypoint.error", "error_type:java.lang.Exception"),
        point("library_entrypoint.complete"));
  }

  @TableTest({
    "scenario            | reasonCode          | resultClass         ",
    "jdk tool            | jdk_tool            | unsupported_binary  ",
    "already initialized | already_initialized | already_instrumented",
    "other java agents   | other-java-agents   | incompatible_library",
    "unknown reason      | foo                 | unknown             "
  })
  void testAbort(String reasonCode, String resultClass) {
    // when
    initTelemetry.onAbort(reasonCode);
    initTelemetry.finish();
    // then
    assertJson(
        "abort",
        resultClass,
        reasonCode,
        point("library_entrypoint.abort", "reason:" + reasonCode));
  }

  @Test
  void testFatalError() {
    // when
    initTelemetry.onFatalError(new Exception("fatal error"));
    initTelemetry.finish();
    // then
    assertJson(
        "error",
        "internal_error",
        "fatal error",
        point("library_entrypoint.error", "error_type:java.lang.Exception"));
  }

  @Test
  void testUnwindRootCause() {
    // when
    initTelemetry.onError(new Exception("top cause", new FileNotFoundException("root cause")));
    initTelemetry.finish();
    // then
    assertJson(
        "error",
        "internal_error",
        "top cause",
        point(
            "library_entrypoint.error",
            "error_type:java.io.FileNotFoundException",
            "error_type:java.lang.Exception"),
        point("library_entrypoint.complete"));
  }

  private void assertJson(
      String result, String resultClass, String resultReason, String... points) {
    String expectedJson =
        "{\"metadata\":{\"result\":\""
            + result
            + "\",\"result_class\":\""
            + resultClass
            + "\",\"result_reason\":\""
            + resultReason
            + "\"},\"points\":["
            + String.join(",", points)
            + "]}";
    assertEquals(expectedJson, capture.json());
  }

  private static String point(String name, String... tags) {
    StringBuilder json = new StringBuilder("{\"name\":\"").append(name).append('"');
    if (tags.length > 0) {
      json.append(",\"tags\":[\"").append(String.join("\",\"", tags)).append("\"]");
    }
    return json.append('}').toString();
  }

  static class Capture implements BootstrapInitializationTelemetry.JsonSender {
    private Object telemetry;

    @Override
    public void send(Object telemetry) {
      this.telemetry = telemetry;
    }

    String json() {
      return telemetry.toString();
    }
  }
}
