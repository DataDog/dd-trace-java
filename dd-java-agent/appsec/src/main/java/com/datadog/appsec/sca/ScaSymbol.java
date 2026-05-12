package com.datadog.appsec.sca;

import javax.annotation.Nullable;

/** A single symbol from sca_cves.json: a class (and optionally a method) to watch for. */
public final class ScaSymbol {

  private final String className; // JVM internal format: "com/foo/Bar"
  @Nullable private final String method; // null = class-level; non-null = future method-level

  public ScaSymbol(String className, @Nullable String method) {
    this.className = className;
    this.method = method;
  }

  /** JVM internal class name with slashes, e.g. {@code "com/foo/Bar"}. */
  public String className() {
    return className;
  }

  /**
   * Method name for method-level tracking, or null for class-level. Currently always null since the
   * database only has class-level symbols.
   */
  @Nullable
  public String method() {
    return method;
  }

  public boolean isClassLevel() {
    return method == null;
  }
}
