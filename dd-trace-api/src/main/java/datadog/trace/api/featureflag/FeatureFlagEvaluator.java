package datadog.trace.api.featureflag;

import java.util.Map;
import java.util.Set;

public interface FeatureFlagEvaluator {

  Resolution<Boolean> evaluate(String key, Boolean defaultValue, Context context);

  Resolution<Integer> evaluate(String key, Integer defaultValue, Context context);

  Resolution<Double> evaluate(String key, Double defaultValue, Context context);

  Resolution<String> evaluate(String key, String defaultValue, Context context);

  Resolution<Object> evaluate(String key, Object defaultValue, Context context);

  void addListener(Listener listener);

  class EvaluationError extends RuntimeException {

    private final String errorCode;
    private final String errorMessage;

    public EvaluationError(final String errorCode, final String errorMessage) {
      this.errorCode = errorCode;
      this.errorMessage = errorMessage;
    }

    public String getErrorCode() {
      return errorCode;
    }

    public String getErrorMessage() {
      return errorMessage;
    }
  }

  interface Listener {

    void onInitialized();

    void onConfigurationChanged();
  }

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
    /** The provider has not been initialized (custom reason for this implementation) */
    NOT_INITIALIZED
  }

  class Resolution<R> {
    private final R value;
    private String variant;
    private String reason;
    private Map<String, Object> flagMetadata;

    public Resolution(final R value) {
      this.value = value;
    }

    public static <R> Resolution<R> defaultResolution(final R value) {
      return new Resolution<>(value).setReason(ResolutionReason.DEFAULT);
    }

    public static <R> Resolution<R> error(final R value) {
      return new Resolution<>(value).setReason(ResolutionReason.ERROR);
    }

    public static <R> Resolution<R> targetingMatch(final R value) {
      return new Resolution<>(value).setReason(ResolutionReason.TARGETING_MATCH);
    }

    public static <R> Resolution<R> notInitialized(final R value) {
      return new Resolution<>(value).setReason(ResolutionReason.NOT_INITIALIZED);
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

    public Resolution<R> setReason(final String reason) {
      this.reason = reason;
      return this;
    }

    public Resolution<R> setReason(final Enum<?> reason) {
      this.reason = reason.name();
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
