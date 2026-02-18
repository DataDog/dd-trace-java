package datadog.crashtracking;

import datadog.crashtracking.dto.CrashLog;
import datadog.crashtracking.parsers.HotspotCrashLogParser;
import datadog.crashtracking.parsers.J9JavacoreParser;

public final class CrashLogParser {

  /** J9 javacore files start with section markers like "0SECTION" */
  private static final String J9_SECTION_MARKER = "0SECTION";

  /** J9 javacore TITLE section identifier */
  private static final String J9_TITLE_MARKER = "TITLE";

  /** Parse a HotSpot crash log (hs_err_pidXXX.log format). */
  public static CrashLog fromHotspotCrashLog(String uuid, String logText) {
    return new HotspotCrashLogParser().parse(uuid, logText);
  }

  /** Parse a J9/OpenJ9 javacore dump file. */
  public static CrashLog fromJ9Javacore(String uuid, String javacoreContent) {
    return new J9JavacoreParser().parse(uuid, javacoreContent);
  }

  /**
   * Auto-detect crash log format and parse accordingly.
   *
   * <p>Detection is based on format-specific markers:
   *
   * <ul>
   *   <li>J9 javacore: Contains "0SECTION" and "TITLE" markers
   *   <li>HotSpot hs_err: Default fallback
   * </ul>
   */
  public static CrashLog parse(String uuid, String content) {
    if (isJ9Javacore(content)) {
      return fromJ9Javacore(uuid, content);
    }
    return fromHotspotCrashLog(uuid, content);
  }

  /** Check if the content appears to be a J9 javacore file. */
  static boolean isJ9Javacore(String content) {
    if (content == null || content.isEmpty()) {
      return false;
    }
    // J9 javacores have a distinctive format with 0SECTION markers
    return content.contains(J9_SECTION_MARKER) && content.contains(J9_TITLE_MARKER);
  }
}
