package com.datadog.appsec.ddwaf;

import com.datadog.appsec.util.StandardizedLogging;
import com.datadog.ddwaf.Waf;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LibSqreenInitialization {
  public static final Waf WAF = initWAF();

  private static Waf initWAF() {
    try {
      boolean simpleLoad = System.getProperty("POWERWAF_SIMPLE_LOAD") != null;
      return new Waf(simpleLoad);
    } catch (Exception e) {
      Logger logger = LoggerFactory.getLogger(LibSqreenInitialization.class);
      logger.warn("Error initializing WAF library", e);
      StandardizedLogging.libddwafCannotBeLoaded(logger, getLibc());
      return null;
    }
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
