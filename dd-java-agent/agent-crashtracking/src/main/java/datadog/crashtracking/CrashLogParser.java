package datadog.crashtracking;

import datadog.crashtracking.dto.CrashLog;
import datadog.crashtracking.parsers.HotspotCrashLogParser;

public final class CrashLogParser {
  public static CrashLog fromHotspotCrashLog(String logText) {
    return new HotspotCrashLogParser().parse(logText);
  }
}
