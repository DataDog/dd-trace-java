package com.datadog.appsec.sca;

/** A single method-level symbol from sca_cves.json: a class and method to watch for. */
public final class ScaSymbol {

  private final String className; // JVM internal format: "com/foo/Bar"
  private final String method;

  public ScaSymbol(String className, String method) {
    this.className = className;
    this.method = method;
  }

  /** JVM internal class name with slashes, e.g. {@code "com/foo/Bar"}. */
  public String className() {
    return className;
  }

  /** Method name for method-level tracking, e.g. {@code "readValue"}. */
  public String method() {
    return method;
  }
}
