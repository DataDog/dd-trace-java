package com.datadog.profiling.async;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** A simple implementation to detect the current architecture */
enum Arch {
  x64("x86_64", "amd64", "k8"),
  x86("x86", "i386", "i486", "i586", "i686"),
  arm("ARM", "arm64"),
  aarch64("aarch64"),
  unknown();

  private final Set<String> identifiers;

  Arch(String... identifiers) {
    this.identifiers = new HashSet<>(Arrays.asList(identifiers));
  }

  public static Arch of(String identifier) {
    for (Arch arch : EnumSet.allOf(Arch.class)) {
      if (arch.identifiers.contains(identifier)) {
        return arch;
      }
    }
    return unknown;
  }

  public static Arch current() {
    return Arch.of(System.getProperty("os.arch"));
  }
}
