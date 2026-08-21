package datadog.smoketest;

import static java.util.Arrays.asList;

import java.util.List;

/**
 * This class defines the log entry messages that are supposed to be excluded from error log checks
 * due to ongoing issues like flaky tests.
 */
final class KnownLogExclusion {
  // Repository-wide known-flaky log lines excluded from the default error-log check.
  private static final List<String> FLAKY_TEST_LOG_EXCLUSION =
      asList(
          // FIXME: Flaky profiler exception. See PROF-11068.
          "ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception in profiling"
              + " thread, trying to continue",
          // FIXME: Flaky profiler exception. See PROF-11072.
          "ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception during"
              + " profiling startup",
          // FIXME: Flaky on Spring Boot (e.g. IastSpringBootSmokeTest) and other HTTP-client
          // suites.
          "I/O reactor terminated abnormally",
          // FIXME: Observed in WildflySmokeTest (semeru8): a successful JMX collector exit.
          "ERROR datadog.trace.agent.jmxfetch.JMXFetch - jmx collector exited with result: 0");

  private KnownLogExclusion() {}

  public static boolean isKnownFlakyTestLogEntry(String line) {
    for (String excluded : FLAKY_TEST_LOG_EXCLUSION) {
      if (line.contains(excluded)) {
        return true;
      }
    }
    return false;
  }
}
