package com.datadog.appsec.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class AppSecVersion {
  public static final String JAVA_VERSION = System.getProperty("java.version", "unknown");
  public static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "unknown");
  public static final String JAVA_VM_VENDOR = System.getProperty("java.vm.vendor", "unknown");
  public static final String VERSION;

  static {
    String v;
    try (final BufferedReader br =
        new BufferedReader(
            new InputStreamReader(
                AppSecVersion.class.getResourceAsStream("/appsec.version"), "UTF-8"))) {
      final StringBuilder sb = new StringBuilder();

      for (int c = br.read(); c != -1; c = br.read()) {
        sb.append((char) c);
      }

      v = sb.toString().trim();
    } catch (final Exception e) {
      v = "unknown";
    }
    VERSION = v;
  }
}
