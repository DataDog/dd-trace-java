package datadog.smoketest;

/**
 * Minimal one-shot batch app launched by {@link SmokeCliAppTest} and {@link SmokeAppLogLevelTest}:
 * echoes a start-up marker and the log level it was launched with, then exits, exercising {@link
 * SmokeCliApp}'s launch/log-capture without the agent.
 */
public final class TestCliApp {
  private TestCliApp() {}

  public static void main(String[] args) {
    System.out.println("CLI-STARTUP-MARKER");
    System.out.println("LOG-LEVEL=" + System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
    System.out.flush();
  }
}
