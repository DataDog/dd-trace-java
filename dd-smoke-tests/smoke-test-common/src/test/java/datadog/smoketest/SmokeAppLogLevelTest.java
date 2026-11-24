package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.AgentBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Checks the log level {@link AbstractSmokeApp} launches an app with: {@code info} by default, and
 * {@code debug} with {@link AbstractSmokeApp.Builder#debugLogs()}. {@link TestCliApp} echoes the
 * level it received, so this asserts the property reaches the child JVM.
 */
class SmokeAppLogLevelTest {

  @RegisterExtension
  static final SmokeCliApp defaultLevel =
      SmokeCliApp.named("log-level-default")
          .mainClass("datadog.smoketest.TestCliApp")
          .backend(AgentBackend.mockAgent())
          .noAgent()
          .build();

  @RegisterExtension
  static final SmokeCliApp debugLevel =
      SmokeCliApp.named("log-level-debug")
          .mainClass("datadog.smoketest.TestCliApp")
          .backend(AgentBackend.mockAgent())
          .noAgent()
          .debugLogs()
          .build();

  @Test
  void launchesWithInfoLevelByDefault() {
    assertTrue(
        defaultLevel.waitForLogLine(line -> line.contains("LOG-LEVEL=info")),
        "app launched with the default info log level");
  }

  @Test
  void debugLogsRaisesTheChildLogLevel() {
    assertTrue(
        debugLevel.waitForLogLine(line -> line.contains("LOG-LEVEL=debug")),
        "debugLogs() launched the app with the debug log level");
  }
}
