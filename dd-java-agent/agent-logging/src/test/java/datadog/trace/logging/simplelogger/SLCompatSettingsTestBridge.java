package datadog.trace.logging.simplelogger;

import java.io.PrintStream;

/** Bridge class to allow tests to access package-private fields of {@link SLCompatSettings}. */
public class SLCompatSettingsTestBridge {
  public static PrintStream printStream(SLCompatSettings settings) {
    return settings.printStream;
  }
}
