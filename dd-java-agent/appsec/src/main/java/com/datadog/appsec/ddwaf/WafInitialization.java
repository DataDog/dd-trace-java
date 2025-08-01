package com.datadog.appsec.ddwaf;

import com.datadog.appsec.util.StandardizedLogging;
import com.datadog.ddwaf.Waf;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WafInitialization {
  public static final boolean ONLINE = initWAF();

  private static boolean initWAF() {
    try {
      boolean simpleLoad = System.getProperty("POWERWAF_SIMPLE_LOAD") != null;
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
    String os = System.getProperty("os.name");
    if ("Linux".equals(os)) {
      File file = new File("/proc/self/maps");
      try (Scanner sc = new Scanner(file, "ISO-8859-1")) {
        while (sc.hasNextLine()) {
          String module = sc.nextLine();
          if (module.contains("libc.musl-") || module.contains("ld-musl-")) {
            return "musl";
          } else if (module.contains("-linux-gnu") || module.contains("libc-")) {
            return "libc";
          }
        }
      } catch (IOException e) {
        // purposefully left blank
      }
    }
    return "unknown";
  }
}
