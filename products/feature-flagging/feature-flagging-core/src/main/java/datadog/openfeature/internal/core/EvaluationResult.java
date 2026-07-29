package datadog.openfeature.internal.core;

/** OpenFeature-independent evaluation result. */
public final class EvaluationResult {

  public enum Reason {
    ERROR,
    DISABLED,
    TARGETING_MATCH,
    SPLIT,
    STATIC,
    DEFAULT
  }

  public enum Error {
    PROVIDER_NOT_READY,
    INVALID_CONTEXT,
    FLAG_NOT_FOUND,
    TARGETING_KEY_MISSING,
    TYPE_MISMATCH,
    PARSE_ERROR,
    GENERAL
  }

  public final Object value;
  public final Reason reason;
  public final Error error;
  public final String errorMessage;
  public final String variant;
  public final String flagKey;
  public final String variationType;
  public final String allocationKey;
  public final Integer splitSerialId;
  public final boolean doLog;

  private EvaluationResult(
      final Object value,
      final Reason reason,
      final Error error,
      final String errorMessage,
      final String variant,
      final String flagKey,
      final String variationType,
      final String allocationKey,
      final Integer splitSerialId,
      final boolean doLog) {
    this.value = value;
    this.reason = reason;
    this.error = error;
    this.errorMessage = errorMessage;
    this.variant = variant;
    this.flagKey = flagKey;
    this.variationType = variationType;
    this.allocationKey = allocationKey;
    this.splitSerialId = splitSerialId;
    this.doLog = doLog;
  }

  public static EvaluationResult value(
      final Object value,
      final Reason reason,
      final String variant,
      final String flagKey,
      final String variationType,
      final String allocationKey,
      final Integer splitSerialId,
      final boolean doLog) {
    return new EvaluationResult(
        value,
        reason,
        null,
        null,
        variant,
        flagKey,
        variationType,
        allocationKey,
        splitSerialId,
        doLog);
  }

  public static EvaluationResult error(
      final Object defaultValue, final Error error, final String message) {
    return new EvaluationResult(
        defaultValue, Reason.ERROR, error, message, null, null, null, null, null, false);
  }
}
