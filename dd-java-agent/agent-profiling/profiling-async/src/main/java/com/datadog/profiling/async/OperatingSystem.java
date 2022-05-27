package com.datadog.profiling.async;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** A simple way to detect the current operating system */
enum OperatingSystem {
  linux("Linux", "linux"),
  macos("Mac OS X", "macOS", "mac"),
  unknown();

  private final Set<String> identifiers;

  OperatingSystem(String... identifiers) {
    this.identifiers = new HashSet<>(Arrays.asList(identifiers));
  }

  public static OperatingSystem of(String identifier) {
    for (OperatingSystem os : EnumSet.allOf(OperatingSystem.class)) {
      if (os.identifiers.contains(identifier)) {
        return os;
      }
    }
    return unknown;
  }

  public static OperatingSystem current() {
    return OperatingSystem.of(System.getProperty("os.name"));
  }
}
