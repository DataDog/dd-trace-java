package datadog.trace.api.telemetry;

/**
 * A single SCA reachability detection: a vulnerable class from a known artifact was loaded at
 * runtime. Produced by {@code ScaReachabilityTransformer} and consumed by {@code
 * ScaReachabilityPeriodicAction} to build the telemetry payload.
 */
public final class ScaReachabilityHit {

  /**
   * JVM internal name for the class initializer. Used as the {@code symbolName} for class-level
   * hits where no specific method was targeted (detection fires at class load time).
   */
  public static final String CLASS_LEVEL_SYMBOL = "<clinit>";

  private final String vulnId;
  private final String artifact;
  private final String version;
  // For class-level hits: the vulnerable library class (FQN, dot notation)
  // For method-level hits: the APPLICATION class that called the vulnerable method (callsite)
  private final String className;
  // For class-level hits: CLASS_LEVEL_SYMBOL ("<clinit>")
  // For method-level hits: the APPLICATION method that called the vulnerable method (callsite)
  private final String symbolName;
  // For class-level hits: 1 (placeholder — no callsite at class load time)
  // For method-level hits: line number in the application code of the call
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
   * For class-level hits: FQN of the vulnerable library class (dot notation). For method-level
   * hits: FQN of the APPLICATION class that called the vulnerable method (callsite), not the
   * vulnerable class itself.
   */
  public String className() {
    return className;
  }

  /**
   * For class-level hits: {@link #CLASS_LEVEL_SYMBOL} ({@code "<clinit>"}). For method-level hits:
   * the APPLICATION method that called the vulnerable method (callsite).
   */
  public String symbolName() {
    return symbolName;
  }

  /**
   * For class-level hits: {@code 1} (placeholder — no specific callsite at class load time). For
   * method-level hits: line number in the application code where the call was made.
   */
  public int line() {
    return line;
  }
}
