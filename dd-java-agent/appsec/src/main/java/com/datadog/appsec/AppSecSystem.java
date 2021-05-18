package com.datadog.appsec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);

  public static void start() {
    log.info("AppSec has started");
  }
}
