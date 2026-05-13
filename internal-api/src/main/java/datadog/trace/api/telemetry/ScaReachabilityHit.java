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
  private final String className; // dot-notation FQN, e.g. "com.foo.Bar"
  private final String symbolName; // "<clinit>" for class-level; method name for method-level
  private final int line; // 1 as placeholder for class-level; actual first line for method-level

  /**
   * Convenience constructor for class-level hits ({@code symbolName = CLASS_LEVEL_SYMBOL}, line =
   * 1).
   */
  public ScaReachabilityHit(String vulnId, String artifact, String version, String className) {
    this(vulnId, artifact, version, className, CLASS_LEVEL_SYMBOL, 1);
  }

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

  /** Fully-qualified class name in dot notation, e.g. {@code "com.foo.Bar"}. */
  public String className() {
    return className;
  }

  /**
   * JVM symbol name: {@code "<clinit>"} for class-level hits, or the method name (e.g. {@code
   * "readValue"}) for method-level hits.
   */
  public String symbolName() {
    return symbolName;
  }

  /** First source line of the detected symbol. {@code 1} for class-level (placeholder). */
  public int line() {
    return line;
  }
}
