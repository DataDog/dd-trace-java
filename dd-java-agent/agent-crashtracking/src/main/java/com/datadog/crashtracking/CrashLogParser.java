package com.datadog.crashtracking;

import com.datadog.crashtracking.dto.CrashLog;
import com.datadog.crashtracking.parsers.HotspotCrashLogParser;

public final class CrashLogParser {
  public static CrashLog fromHotspotCrashLog(String logText) {
    return new HotspotCrashLogParser().parse(logText);
  }
}
