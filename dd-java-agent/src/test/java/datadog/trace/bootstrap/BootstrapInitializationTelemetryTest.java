package datadog.trace.bootstrap;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

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
        "[{name: 'library_entrypoint.complete'}]");
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
        "[{name: 'library_entrypoint.error', tags: ['error_type:some reason']},"
            + " {name: 'library_entrypoint.complete'}]");
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
        "[{name: 'library_entrypoint.error', tags: ['error_type:java.lang.Exception']},"
            + " {name: 'library_entrypoint.complete'}]");
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
        "[{name: 'library_entrypoint.abort', tags: ['reason:" + reasonCode + "']}]");
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
        "[{name: 'library_entrypoint.error', tags: ['error_type:java.lang.Exception']}]");
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
        "[{name: 'library_entrypoint.error',"
            + " tags: ['error_type:java.io.FileNotFoundException', 'error_type:java.lang.Exception']},"
            + " {name: 'library_entrypoint.complete'}]");
  }

  private void assertJson(
      String result, String resultClass, String resultReason, String expectedPoints) {
    String json = capture.json();
    assertThatJson(json).node("metadata").isObject().hasSize(3);
    assertThatJson(json).node("metadata.result").isEqualTo(result);
    assertThatJson(json).node("metadata.result_class").isEqualTo(resultClass);
    assertThatJson(json).node("metadata.result_reason").isEqualTo(resultReason);
    assertThatJson(json).node("points").isEqualTo(expectedPoints);
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
