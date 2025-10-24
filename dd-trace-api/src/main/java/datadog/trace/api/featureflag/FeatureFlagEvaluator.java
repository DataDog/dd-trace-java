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
    ERROR,
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
