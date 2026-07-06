package datadog.trace.api.telemetry;

/**
 * A single SCA reachability detection: a vulnerable method from a known artifact was called at
 * runtime. Produced by {@code ScaReachabilityTransformer} and consumed by {@code
 * ScaReachabilityPeriodicAction} to build the telemetry payload.
 */
public final class ScaReachabilityHit {

  private final String vulnId;
  private final String artifact;
  private final String version;
  // FQN of the APPLICATION class that called the vulnerable method (callsite, dot notation)
  private final String className;
  // The APPLICATION method that called the vulnerable method (callsite)
  private final String symbolName;
  // Line number in the application code where the call was made
  private final int line;

  public ScaReachabilityHit(
      String vulnId,
      String artifact,
      String version,
      String className,
      String symbolName,
      int line) {
    this.vulnId = vulnId;
    this.artifact = artifact;
    this.version = version;
    this.className = className;
    this.symbolName = symbolName;
    this.line = line;
  }

  /** GHSA identifier, e.g. {@code "GHSA-645p-88qh-w398"}. */
  public String vulnId() {
    return vulnId;
  }

  /** Maven coordinate, e.g. {@code "com.fasterxml.jackson.core:jackson-databind"}. */
  public String artifact() {
    return artifact;
  }

  public String version() {
    return version;
  }

  /**
   * FQN of the APPLICATION class that called the vulnerable method (callsite, dot notation). This
   * is the caller frame, not the vulnerable class itself.
   */
  public String className() {
    return className;
  }

  /** The APPLICATION method that called the vulnerable method (callsite). */
  public String symbolName() {
    return symbolName;
  }

  /** Line number in the application code where the call was made. */
  public int line() {
    return line;
  }
}
