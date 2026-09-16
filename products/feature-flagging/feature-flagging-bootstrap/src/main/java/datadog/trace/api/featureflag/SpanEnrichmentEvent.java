package datadog.trace.api.featureflag;

/**
 * Bootstrap-classloader carrier for a single feature-flag evaluation that must be reflected onto
 * the local-root APM span (span enrichment). It crosses the app-classloader → agent-classloader
 * boundary through {@link FeatureFlaggingGateway}, so it holds <b>only JDK types</b> — never an
 * OpenFeature or tracer type — exactly like the sibling exposure/config payloads.
 *
 * <p>The capture side (the published {@code dd-openfeature} provider) decides which of the two
 * shapes applies and unwraps any OpenFeature {@code Value} to its native Java form before
 * dispatching; the write side (agent-side listener) resolves the active local root and accumulates.
 *
 * <ul>
 *   <li><b>serial-id</b> ({@link #serialId(int, boolean, String)}) — a UFC split with a serial id,
 *       plus the {@code doLog} flag and the (optional) targeting key used to record the subject.
 *   <li><b>runtime-default</b> ({@link #runtimeDefault(String, Object)}) — a flag that resolved to
 *       its runtime default (missing variant); carries the flag key and the native default value.
 * </ul>
 */
public final class SpanEnrichmentEvent {

  private final boolean serialIdPresent;
  private final int serialId;
  private final boolean doLog;
  private final String targetingKey;
  private final String flagKey;
  private final Object defaultValue;

  private SpanEnrichmentEvent(
      final boolean serialIdPresent,
      final int serialId,
      final boolean doLog,
      final String targetingKey,
      final String flagKey,
      final Object defaultValue) {
    this.serialIdPresent = serialIdPresent;
    this.serialId = serialId;
    this.doLog = doLog;
    this.targetingKey = targetingKey;
    this.flagKey = flagKey;
    this.defaultValue = defaultValue;
  }

  /** A UFC split evaluation carrying a serial id (and, when {@code doLog}, a subject). */
  public static SpanEnrichmentEvent serialId(
      final int serialId, final boolean doLog, final String targetingKey) {
    return new SpanEnrichmentEvent(true, serialId, doLog, targetingKey, null, null);
  }

  /**
   * A runtime-default evaluation (missing variant). {@code value} must already be unwrapped to a
   * native Java type (Map/List/scalar/null) by the caller.
   */
  public static SpanEnrichmentEvent runtimeDefault(final String flagKey, final Object value) {
    return new SpanEnrichmentEvent(false, 0, false, null, flagKey, value);
  }

  /** True for the serial-id shape; false for the runtime-default shape. */
  public boolean hasSerialId() {
    return serialIdPresent;
  }

  public int serialId() {
    return serialId;
  }

  public boolean doLog() {
    return doLog;
  }

  public String targetingKey() {
    return targetingKey;
  }

  public String flagKey() {
    return flagKey;
  }

  public Object defaultValue() {
    return defaultValue;
  }
}
