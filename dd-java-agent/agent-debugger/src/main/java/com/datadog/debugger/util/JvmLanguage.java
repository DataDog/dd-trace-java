package com.datadog.debugger.util;

public enum JvmLanguage {
  JAVA,
  KOTLIN,
  SCALA,
  GROOVY,
  UNKNOWN;

  public static JvmLanguage of(String sourceFile) {
    if (sourceFile == null) {
      return UNKNOWN;
    }
    if (sourceFile.endsWith(".java")) {
      return JAVA;
    }
    if (sourceFile.endsWith(".kt")) {
      return KOTLIN;
    }
    if (sourceFile.endsWith(".scala")) {
      return SCALA;
    }
    if (sourceFile.endsWith(".groovy")) {
      return GROOVY;
    }
    return UNKNOWN;
  }
}
