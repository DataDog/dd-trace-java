package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.TraceBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies {@link SmokeApp#assertNoErrorLogs()} against a real launched app: it reads the captured
 * log and flags error lines. {@code skipErrorLogCheck()} disables the automatic teardown check so
 * the deliberately-logged error doesn't fail the class — the assertion is exercised explicitly.
 */
class ErrorLogSmokeTest {

  @RegisterExtension
  static final SmokeApp app =
      SmokeApp.named("error-logger")
          .mainClass("datadog.smoketest.TestServerApp")
          .args("--server.port=${app.httpPort}")
          .backend(TraceBackend.mockAgent())
          .noAgent()
          .skipErrorLogCheck()
          .build();

  @Test
  void detectsErrorLinesInTheLog() {
    app.get("/hello");
    app.assertNoErrorLogs(); // no error logged yet

    app.get("/error"); // the app logs "ERROR simulated application error"
    app.awaitLogLine(line -> line.contains("ERROR simulated")); // ensure it reached the log file

    AssertionError failure = assertThrows(AssertionError.class, app::assertNoErrorLogs);
    assertTrue(failure.getMessage().contains("ERROR simulated"), failure.getMessage());
  }
}
