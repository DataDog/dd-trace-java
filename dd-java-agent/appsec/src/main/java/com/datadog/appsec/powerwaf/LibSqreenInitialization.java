package com.datadog.appsec.powerwaf;

import io.sqreen.powerwaf.Powerwaf;
import org.slf4j.LoggerFactory;

public class LibSqreenInitialization {
  public static final boolean ONLINE = initPowerWAF();

  private static boolean initPowerWAF() {
    try {
      boolean simpleLoad = System.getProperty("POWERWAF_SIMPLE_LOAD") != null;
      Powerwaf.initialize(simpleLoad);
    } catch (Exception e) {
      LoggerFactory.getLogger(LibSqreenInitialization.class)
          .error(
              "Error initializing libsqreen. "
                  + "In-app WAF and detailed metrics will not be available",
              e);
      return false;
    }

    return true;
  }
}
