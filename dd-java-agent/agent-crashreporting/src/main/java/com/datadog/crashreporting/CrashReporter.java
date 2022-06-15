package com.datadog.crashreporting;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Crash Reporter implementation */
public class CrashReporter {

  private static final Logger log = LoggerFactory.getLogger(CrashReporter.class);

  /**
   * Main entry point into crash reporter. This gets invoked through -XX:OnError="java
   * com.datadog.crashreporting.agent.CrashReporter ..."
   */
  public static void main(String[] args) throws IOException {}
}
