package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One case of a scale suite of the shared FFE fixtures, with its expected value and reason, and the
 * selected {@code serialId}, {@code doLog}, and split features when the case expects them.
 *
 * <p>JSON values are compared by numeric value: a JSON integer can come back as an integer or as a
 * double, so that one check runs on every evaluator. {@code CanonicalFixtureTest} checks the types.
 */
final class ScaleCase {
  private final String flag;
  private final String variationType;
  private final Object defaultValue;
  private final Object expectedValue;
  private final String expectedReason;
  private final boolean checksSerialId;
  private final Number expectedSerialId;
  private final Boolean expectedDoLog;
  private final Map<?, ?> expectedFeatures;
  private final String targetingKey;
  private final EvaluationContext context;

  ScaleCase(final Map<?, ?> json) {
    this.flag = (String) json.get("flag");
    this.variationType = (String) json.get("variationType");
    final Map<?, ?> result = (Map<?, ?>) json.get("result");
    if (!Arrays.asList("value", "reason", "serialId", "doLog", "features")
        .containsAll(result.keySet())) {
      throw new IllegalStateException(
          "Unhandled expectations for " + json.get("flag") + ": " + result.keySet());
    }
    this.defaultValue = typed(json.get("defaultValue"));
    this.expectedValue = result.get("value");
    this.expectedReason = (String) result.get("reason");
    this.checksSerialId = result.containsKey("serialId");
    this.expectedSerialId = (Number) result.get("serialId");
    this.expectedDoLog = (Boolean) result.get("doLog");
    this.expectedFeatures = (Map<?, ?>) result.get("features");
    this.targetingKey = (String) json.get("targetingKey");
    final MutableContext context =
        new MutableContext(Value.objectToValue(json.get("attributes")).asStructure().asMap());
    context.setTargetingKey(targetingKey);
    this.context = context;
  }

  ProviderEvaluation<?> evaluate(final Provider provider) {
    return evaluate(provider, context);
  }

  /** Evaluates this case with another context, such as a layered one wrapping {@link #context}. */
  ProviderEvaluation<?> evaluate(final Provider provider, final EvaluationContext context) {
    switch (variationType) {
      case "BOOLEAN":
        return provider.getBooleanEvaluation(flag, (Boolean) defaultValue, context);
      case "INTEGER":
        return provider.getIntegerEvaluation(flag, (Integer) defaultValue, context);
      case "NUMERIC":
        return provider.getDoubleEvaluation(flag, (Double) defaultValue, context);
      case "STRING":
        return provider.getStringEvaluation(flag, (String) defaultValue, context);
      case "JSON":
        return provider.getObjectEvaluation(flag, (Value) defaultValue, context);
      default:
        throw new IllegalArgumentException("Unsupported variationType: " + variationType);
    }
  }

  /** Evaluates this case through an OpenFeature client, with its hooks, and another context. */
  FlagEvaluationDetails<?> evaluate(final Client client, final EvaluationContext context) {
    switch (variationType) {
      case "BOOLEAN":
        return client.getBooleanDetails(flag, (Boolean) defaultValue, context);
      case "INTEGER":
        return client.getIntegerDetails(flag, (Integer) defaultValue, context);
      case "NUMERIC":
        return client.getDoubleDetails(flag, (Double) defaultValue, context);
      case "STRING":
        return client.getStringDetails(flag, (String) defaultValue, context);
      case "JSON":
        return client.getObjectDetails(flag, (Value) defaultValue, context);
      default:
        throw new IllegalArgumentException("Unsupported variationType: " + variationType);
    }
  }

  void check(final ProviderEvaluation<?> details) {
    final Object actual =
        details.getValue() instanceof Value
            ? plain((Value) details.getValue())
            : details.getValue();
    if (!sameValue(expectedValue, actual)
        || !Objects.equals(expectedReason, details.getReason())
        || details.getErrorCode() != null) {
      throw new IllegalStateException(
          "Unexpected result for "
              + flag
              + " ("
              + targetingKey
              + "): expected "
              + expectedReason
              + " "
              + abbreviate(expectedValue)
              + ", got "
              + details.getReason()
              + " "
              + abbreviate(actual));
    }
    final Map<String, Object> metadata = details.getFlagMetadata().asUnmodifiableMap();
    if (checksSerialId) {
      final Object serialId = metadata.get(SPLIT_SERIAL_ID);
      if (expectedSerialId == null ? serialId != null : !sameValue(expectedSerialId, serialId)) {
        throw unexpectedMetadata("serialId", expectedSerialId, serialId);
      }
    }
    // Without span enrichment, as the scale suites run, an allocation that does not log carries no
    // doLog key and no serial ID, so a missing doLog means false.
    final Object doLog = metadata.getOrDefault(DO_LOG, false);
    if (expectedDoLog != null && !expectedDoLog.equals(doLog)) {
      throw unexpectedMetadata("doLog", expectedDoLog, doLog);
    }
    if (expectedFeatures != null) {
      final Map<String, Object> features = new LinkedHashMap<>();
      features.put(
          "HOOK",
          SplitFeatureMetadata.featuresWithPrefix(details.getFlagMetadata(), FEATURE_PREFIX));
      features.put(
          "EXPOSURE",
          SplitFeatureMetadata.featuresWithPrefix(
              details.getFlagMetadata(), EXPOSURE_FEATURE_PREFIX));
      features.put(
          "EVALUATION",
          SplitFeatureMetadata.featuresWithPrefix(
              details.getFlagMetadata(), EVALUATION_FEATURE_PREFIX));
      if (!sameValue(expectedFeatures, features)) {
        throw unexpectedMetadata("features", expectedFeatures, features);
      }
    }
  }

  private IllegalStateException unexpectedMetadata(
      final String field, final Object expected, final Object actual) {
    return new IllegalStateException(
        "Unexpected "
            + field
            + " for "
            + flag
            + " ("
            + targetingKey
            + "): expected "
            + abbreviate(expected)
            + ", got "
            + abbreviate(actual));
  }

  EvaluationContext context() {
    return context;
  }

  String describe() {
    return flag + " (" + targetingKey + ") -> " + expectedReason;
  }

  /** Converts a fixture value to the type that the provider takes for this variation type. */
  private Object typed(final Object value) {
    if (value == null) {
      return null;
    }
    switch (variationType) {
      case "INTEGER":
        return ((Number) value).intValue();
      case "NUMERIC":
        return ((Number) value).doubleValue();
      case "JSON":
        return Value.objectToValue(value);
      default:
        return value;
    }
  }

  /** Converts a returned value to maps, lists, strings, numbers, booleans and null. */
  private static Object plain(final Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isStructure()) {
      final Map<String, Object> map = new LinkedHashMap<>();
      value.asStructure().asMap().forEach((k, v) -> map.put(k, plain(v)));
      return map;
    }
    if (value.isList()) {
      final List<Object> list = new ArrayList<>();
      for (final Value item : value.asList()) {
        list.add(plain(item));
      }
      return list;
    }
    return value.asObject();
  }

  /** Equality that compares numbers by value, so 5 and 5.0 are the same. */
  private static boolean sameValue(final Object expected, final Object actual) {
    if (expected instanceof Number && actual instanceof Number) {
      return new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString())) == 0;
    }
    if (expected instanceof Map && actual instanceof Map) {
      final Map<?, ?> e = (Map<?, ?>) expected;
      final Map<?, ?> a = (Map<?, ?>) actual;
      if (e.size() != a.size()) {
        return false;
      }
      for (final Map.Entry<?, ?> entry : e.entrySet()) {
        if (!a.containsKey(entry.getKey()) || !sameValue(entry.getValue(), a.get(entry.getKey()))) {
          return false;
        }
      }
      return true;
    }
    if (expected instanceof List && actual instanceof List) {
      final List<?> e = (List<?>) expected;
      final List<?> a = (List<?>) actual;
      if (e.size() != a.size()) {
        return false;
      }
      for (int i = 0; i < e.size(); i++) {
        if (!sameValue(e.get(i), a.get(i))) {
          return false;
        }
      }
      return true;
    }
    return Objects.equals(expected, actual);
  }

  private static String abbreviate(final Object value) {
    final String text = String.valueOf(value);
    return text.length() <= 200 ? text : text.substring(0, 200) + "... (" + text.length() + ")";
  }
}
