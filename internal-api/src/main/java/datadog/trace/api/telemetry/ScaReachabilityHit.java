package datadog.trace.api.telemetry;

/**
 * A single SCA reachability detection: a vulnerable class from a known artifact was loaded at
 * runtime. Produced by {@code ScaReachabilityTransformer} and consumed by {@code
 * ScaReachabilityPeriodicAction} to build the telemetry payload.
 */
public final class ScaReachabilityHit {

  private final String vulnId;
  private final String artifact;
  private final String version;
  private final String className; // dot-notation FQN, e.g. "com.foo.Bar"

  public ScaReachabilityHit(String vulnId, String artifact, String version, String className) {
    this.vulnId = vulnId;
    this.artifact = artifact;
    this.version = version;
    this.className = className;
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
}
