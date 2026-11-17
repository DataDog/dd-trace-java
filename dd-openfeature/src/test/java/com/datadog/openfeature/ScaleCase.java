package com.datadog.openfeature;

import dev.openfeature.sdk.EvaluationContext;
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
 * One case of a scale suite of the shared FFE fixtures, with its expected value, reason and error
 * code.
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
  private final String expectedErrorCode;
  private final String targetingKey;
  private final EvaluationContext context;

  ScaleCase(final Map<?, ?> json) {
    this.flag = (String) json.get("flag");
    this.variationType = (String) json.get("variationType");
    final Map<?, ?> result = (Map<?, ?>) json.get("result");
    if (!Arrays.asList("value", "reason", "errorCode").containsAll(result.keySet())) {
      throw new IllegalStateException(
          "Unhandled expectations for " + json.get("flag") + ": " + result.keySet());
    }
    this.defaultValue = typed(json.get("defaultValue"));
    this.expectedValue = result.get("value");
    this.expectedReason = (String) result.get("reason");
    this.expectedErrorCode = (String) result.get("errorCode");
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

  void check(final ProviderEvaluation<?> details) {
    final Object actual =
        details.getValue() instanceof Value
            ? plain((Value) details.getValue())
            : details.getValue();
    final String errorCode = details.getErrorCode() == null ? null : details.getErrorCode().name();
    if (!sameValue(expectedValue, actual)
        || !Objects.equals(expectedReason, details.getReason())
        || !Objects.equals(expectedErrorCode, errorCode)) {
      throw new IllegalStateException(
          "Unexpected result for "
              + flag
              + " ("
              + targetingKey
              + "): expected "
              + expectedReason
              + " "
              + expectedErrorCode
              + " "
              + abbreviate(expectedValue)
              + ", got "
              + details.getReason()
              + " "
              + errorCode
              + " "
              + abbreviate(actual));
    }
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
