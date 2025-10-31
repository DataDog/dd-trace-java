package datadog.trace.api.featureflag;

import java.util.Map;
import java.util.Set;

public interface FeatureFlagEvaluator {

  Resolution<Boolean> evaluate(String key, Boolean defaultValue, Context context);

  Resolution<Integer> evaluate(String key, Integer defaultValue, Context context);

  Resolution<Double> evaluate(String key, Double defaultValue, Context context);

  Resolution<String> evaluate(String key, String defaultValue, Context context);

  Resolution<Object> evaluate(String key, Object defaultValue, Context context);

  enum ResolutionReason {
    /** The resolved value is static (no dynamic evaluation occurred) */
    STATIC,
    /** The resolved value fell back to a default (no dynamic evaluation or it yielded no result) */
    DEFAULT,
    /** The resolved value resulted from dynamic evaluation (rule or user-targeting) */
    TARGETING_MATCH,
    /** The resolved value was determined through pseudorandom assignment */
    SPLIT,
    /** The resolved value was retrieved from a cache */
    CACHED,
    /** The flag is disabled in the management system */
    DISABLED,
    /** The reason for the resolved value could not be determined */
    UNKNOWN,
    /** The resolved value is non-authoritative or possibly out of date */
    STALE,
    /** The resolved value resulted from an error during evaluation */
    ERROR,
  }

  enum ResolutionError {
    PROVIDER_NOT_READY,
    FLAG_NOT_FOUND,
    PARSE_ERROR,
    TYPE_MISMATCH,
    TARGETING_KEY_MISSING,
    INVALID_CONTEXT,
    GENERAL,
    PROVIDER_FATAL
  }

  class Resolution<R> {
    private final String flagKey;
    private final R value;
    private String variant;
    private String reason;
    private Map<String, Object> flagMetadata;
    private ResolutionError errorCode;
    private String errorMessage;

    public Resolution(final String flag, final R value) {
      this.flagKey = flag;
      this.value = value;
    }

    public static <R> Resolution<R> defaultResolution(final String flag, final R value) {
      return new Resolution<>(flag, value).setReason(ResolutionReason.DEFAULT);
    }

    public static <R> Resolution<R> targetingMatch(final String flag, final R value) {
      return new Resolution<>(flag, value).setReason(ResolutionReason.TARGETING_MATCH);
    }

    public static <R> Resolution<R> disabled(final String flag, final R value) {
      return new Resolution<>(flag, value).setReason(ResolutionReason.DISABLED);
    }

    public static <R> Resolution<R> providerNotReady(final String flag, final R value) {
      return new Resolution<>(flag, value).setErrorCode(ResolutionError.PROVIDER_NOT_READY);
    }

    public static <R> Resolution<R> invalidContext(final String flag, final R value) {
      return new Resolution<>(flag, value).setErrorCode(ResolutionError.INVALID_CONTEXT);
    }

    public static <R> Resolution<R> targetingKeyMissing(final String flag, final R value) {
      return new Resolution<>(flag, value).setErrorCode(ResolutionError.TARGETING_KEY_MISSING);
    }

    public static <R> Resolution<R> flagNotFound(final String flag, final R value) {
      return new Resolution<>(flag, value).setErrorCode(ResolutionError.FLAG_NOT_FOUND);
    }

    public static <R> Resolution<R> generalError(
        final String flag, final R value, final String message) {
      return new Resolution<>(flag, value)
          .setErrorCode(ResolutionError.GENERAL)
          .setErrorMessage(message);
    }

    public static <R> Resolution<R> typeMissmatch(
        final String flag, final R value, final String message) {
      return new Resolution<>(flag, value)
          .setErrorCode(ResolutionError.TYPE_MISMATCH)
          .setErrorMessage(message);
    }

    public String getFlagKey() {
      return flagKey;
    }

    public String getReason() {
      return reason;
    }

    public R getValue() {
      return value;
    }

    public String getVariant() {
      return variant;
    }

    public Map<String, Object> getFlagMetadata() {
      return flagMetadata;
    }

    public ResolutionError getErrorCode() {
      return errorCode;
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    public Resolution<R> setReason(final ResolutionReason reason) {
      this.reason = reason.name();
      return this;
    }

    public Resolution<R> setErrorCode(final ResolutionError error) {
      this.errorCode = error;
      return this;
    }

    public Resolution<R> setErrorMessage(final String message) {
      this.errorMessage = message;
      return this;
    }

    public Resolution<R> setVariant(final String variant) {
      this.variant = variant;
      return this;
    }

    public Resolution<R> setFlagMetadata(final Map<String, Object> flagMetadata) {
      this.flagMetadata = flagMetadata;
      return this;
    }
  }

  interface Context {
    String getTargetingKey();

    Set<String> keySet();

    Object getValue(String key);
  }
}
