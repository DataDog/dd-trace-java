package com.datadog.debugger.util;

/** Identifies class files belonging to the debugger's own internal packages. */
public class DebuggerInternalPackages {
  private static final String[] SKIPPED_PACKAGES = {
    "com/datadog/debugger/agent/",
    "com/datadog/debugger/codeorigin/",
    "com/datadog/debugger/exception/",
    "com/datadog/debugger/instrumentation/",
    "com/datadog/debugger/probe/",
    "com/datadog/debugger/sink/",
    "com/datadog/debugger/symbol/",
    "com/datadog/debugger/uploader/",
    "com/datadog/debugger/util/"
  };

  /**
   * @param classFilePath slash-separated class file path (e.g. "com/datadog/debugger/agent/Foo")
   * @return true if the class belongs to a debugger-internal package that must never be
   *     re-transformed/parsed, to avoid a re-entrant LinkageError while it is being loaded.
   */
  public static boolean isDebuggerInternalClass(String classFilePath) {
    if (classFilePath == null || !classFilePath.startsWith("com/datadog/debugger/")) {
      return false;
    }
    for (String pkg : SKIPPED_PACKAGES) {
      if (classFilePath.startsWith(pkg)) {
        return true;
      }
    }
    return false;
  }
}
