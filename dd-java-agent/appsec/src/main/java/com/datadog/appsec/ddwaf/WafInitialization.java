package com.datadog.appsec.ddwaf;

import com.datadog.appsec.util.StandardizedLogging;
import com.datadog.ddwaf.Waf;
import datadog.environment.SystemProperties;
import datadog.trace.api.internal.VisibleForTesting;
import java.io.File;
import java.util.Scanner;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WafInitialization {
  public static final boolean ONLINE = initWAF();

  private static boolean initWAF() {
    try {
      boolean simpleLoad = SystemProperties.get("POWERWAF_SIMPLE_LOAD") != null;
      Waf.initialize(simpleLoad);
    } catch (Throwable e) {
      Logger logger = LoggerFactory.getLogger(WafInitialization.class);
      logger.warn("Error initializing WAF library", e);
      StandardizedLogging.libddwafCannotBeLoaded(logger, getLibc());
      return false;
    }

    return true;
  }

  private static String getLibc() {
    return getLibc(
        () -> SystemProperties.get("os.name"),
        () -> new Scanner(new File("/proc/self/maps"), "ISO-8859-1"));
  }

  @VisibleForTesting
  static String getLibc(Callable<String> osName, Callable<Scanner> opener) {
    try {
      if ("Linux".equals(osName.call())) {
        try (Scanner sc = opener.call()) {
          while (sc.hasNextLine()) {
            String module = sc.nextLine();
            if (module.contains("libc.musl-") || module.contains("ld-musl-")) {
              return "musl";
            } else if (module.contains("-linux-gnu") || module.contains("libc-")) {
              return "libc";
            }
          }
        }
      }
    } catch (Throwable e) {
      LoggerFactory.getLogger(WafInitialization.class).debug("Error detecting libc", e);
    }
    return "unknown";
  }
}
