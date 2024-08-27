package datadog.trace.api.time;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class TimeUtils {

  /** Number followed by an optional time unit of hours (h), minutes (m), or seconds (s). */
  private static final Pattern SIMPLE_DELAY_PATTERN = Pattern.compile("(\\d+)([HhMmSs]?)");

  /**
   * Parses the string as a simple delay, such as "30s" or "10m".
   *
   * @param delayString number followed by an optional time unit
   * @return delay in seconds; -1 if the string cannot be parsed
   */
  public static long parseSimpleDelay(String delayString) {
    if (null != delayString) {
      Matcher delayMatcher = SIMPLE_DELAY_PATTERN.matcher(delayString);
      if (delayMatcher.matches()) {
        long delay = Integer.parseInt(delayMatcher.group(1));
        String unit = delayMatcher.group(2);
        if ("H".equalsIgnoreCase(unit)) {
          return TimeUnit.HOURS.toSeconds(delay);
        } else if ("M".equalsIgnoreCase(unit)) {
          return TimeUnit.MINUTES.toSeconds(delay);
        } else {
          return delay; // already in seconds
        }
      }
    }
    return -1; // unrecognized
  }

  private TimeUtils() {}
}
