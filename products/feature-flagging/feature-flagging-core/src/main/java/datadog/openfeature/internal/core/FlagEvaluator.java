package datadog.openfeature.internal.core;

import datadog.openfeature.internal.core.ConfigurationSnapshot.Allocation;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Condition;
import datadog.openfeature.internal.core.ConfigurationSnapshot.ConditionOperator;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Flag;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Rule;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Shard;
import datadog.openfeature.internal.core.ConfigurationSnapshot.ShardRange;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Split;
import datadog.openfeature.internal.core.ConfigurationSnapshot.ValueType;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Variant;
import datadog.openfeature.internal.core.EvaluationResult.Error;
import datadog.openfeature.internal.core.EvaluationResult.Reason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Evaluates immutable UFC snapshots without OpenFeature or agent classes. */
public final class FlagEvaluator {

  public enum ValueKind {
    BOOLEAN,
    STRING,
    INTEGER,
    DOUBLE,
    OBJECT
  }

  public EvaluationResult evaluate(
      final ConfigurationSnapshot snapshot,
      final ValueKind target,
      final String key,
      final Object defaultValue,
      final EvaluationContext context) {
    try {
      if (snapshot == null) {
        return EvaluationResult.error(defaultValue, Error.PROVIDER_NOT_READY, null);
      }
      if (context == null) {
        return EvaluationResult.error(defaultValue, Error.INVALID_CONTEXT, null);
      }
      final Flag flag = snapshot.flags.get(key);
      if (flag == null) {
        return EvaluationResult.error(defaultValue, Error.FLAG_NOT_FOUND, null);
      }
      if (!flag.enabled) {
        return EvaluationResult.value(
            defaultValue, Reason.DISABLED, null, flag.key, typeName(flag), null, null, false);
      }
      if (flag.allocations == null) {
        return EvaluationResult.error(
            defaultValue, Error.GENERAL, "Missing allocations for flag " + key);
      }

      final long now = System.currentTimeMillis();
      for (final Allocation allocation : flag.allocations) {
        if (!isActive(allocation, now)
            || (!isEmpty(allocation.rules) && !evaluateRules(allocation.rules, context))) {
          continue;
        }
        if (isEmpty(allocation.splits)) {
          continue;
        }
        for (final Split split : allocation.splits) {
          if (isEmpty(split.shards)) {
            return resolve(target, key, defaultValue, flag, allocation, split);
          }
          if (context.targetingKey() == null) {
            return EvaluationResult.error(defaultValue, Error.TARGETING_KEY_MISSING, null);
          }
          boolean matches = true;
          for (final Shard shard : split.shards) {
            if (!matchesShard(shard, context.targetingKey())) {
              matches = false;
              break;
            }
          }
          if (matches) {
            return resolve(target, key, defaultValue, flag, allocation, split);
          }
        }
      }
      return EvaluationResult.value(
          defaultValue, Reason.DEFAULT, null, flag.key, typeName(flag), null, null, false);
    } catch (final PatternSyntaxException e) {
      return EvaluationResult.error(defaultValue, Error.PARSE_ERROR, e.getMessage());
    } catch (final NumberFormatException e) {
      return EvaluationResult.error(defaultValue, Error.TYPE_MISMATCH, e.getMessage());
    } catch (final RuntimeException e) {
      return EvaluationResult.error(defaultValue, Error.GENERAL, e.getMessage());
    }
  }

  private static EvaluationResult resolve(
      final ValueKind target,
      final String key,
      final Object defaultValue,
      final Flag flag,
      final Allocation allocation,
      final Split split) {
    final Variant variant = flag.variations.get(split.variationKey);
    if (variant == null) {
      return EvaluationResult.error(
          defaultValue, Error.GENERAL, "Variant not found for: " + split.variationKey);
    }
    if (!isCompatible(target, flag.variationType)) {
      return EvaluationResult.error(
          defaultValue,
          Error.TYPE_MISMATCH,
          "Requested type " + target + " does not match " + flag.variationType);
    }

    final Object value;
    try {
      value = mapValue(target, variant.value);
    } catch (final NumberFormatException e) {
      return EvaluationResult.error(
          defaultValue,
          Error.PARSE_ERROR,
          "Variant '" + variant.key + "' does not match " + flag.variationType);
    }

    final Reason reason =
        !isEmpty(allocation.rules)
            ? Reason.TARGETING_MATCH
            : !isEmpty(split.shards) ? Reason.SPLIT : Reason.STATIC;
    return EvaluationResult.value(
        value,
        reason,
        variant.key,
        key,
        typeName(flag),
        allocation.key,
        split.serialId,
        allocation.doLog);
  }

  public static Object mapValue(final ValueKind target, final Object value) {
    if (value == null || target == ValueKind.OBJECT) {
      return value;
    }
    switch (target) {
      case STRING:
        return String.valueOf(value);
      case BOOLEAN:
        return value instanceof Number
            ? Boolean.valueOf(((Number) value).doubleValue() != 0)
            : Boolean.valueOf(String.valueOf(value));
      case INTEGER:
        return value instanceof Number
            ? ((Number) value).intValue()
            : (int) Double.parseDouble(String.valueOf(value));
      case DOUBLE:
        return value instanceof Number
            ? ((Number) value).doubleValue()
            : Double.parseDouble(String.valueOf(value));
      default:
        throw new IllegalArgumentException("Unsupported value kind: " + target);
    }
  }

  private static boolean isActive(final Allocation allocation, final long now) {
    return (allocation.startAtMillis == null || now >= allocation.startAtMillis)
        && (allocation.endAtMillis == null || now <= allocation.endAtMillis);
  }

  private static boolean evaluateRules(final List<Rule> rules, final EvaluationContext context) {
    for (final Rule rule : rules) {
      if (isEmpty(rule.conditions)) {
        continue;
      }
      boolean matches = true;
      for (final Condition condition : rule.conditions) {
        if (!evaluateCondition(condition, context)) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return true;
      }
    }
    return false;
  }

  private static boolean evaluateCondition(
      final Condition condition, final EvaluationContext context) {
    final Object attribute = context.attribute(condition.attribute);
    if (condition.operator == ConditionOperator.IS_NULL) {
      final boolean expectedNull =
          !(condition.value instanceof Boolean) || (Boolean) condition.value;
      return (attribute == null) == expectedNull;
    }
    if (attribute == null) {
      return false;
    }
    switch (condition.operator) {
      case MATCHES:
        return Pattern.compile(String.valueOf(condition.value))
            .matcher(String.valueOf(attribute))
            .find();
      case NOT_MATCHES:
        return !Pattern.compile(String.valueOf(condition.value))
            .matcher(String.valueOf(attribute))
            .find();
      case ONE_OF:
        return oneOf(attribute, condition.value);
      case NOT_ONE_OF:
        return !oneOf(attribute, condition.value);
      case GTE:
        return compare(attribute, condition.value, (a, b) -> a >= b);
      case GT:
        return compare(attribute, condition.value, (a, b) -> a > b);
      case LTE:
        return compare(attribute, condition.value, (a, b) -> a <= b);
      case LT:
        return compare(attribute, condition.value, (a, b) -> a < b);
      default:
        return false;
    }
  }

  private static boolean oneOf(final Object attribute, final Object values) {
    if (!(values instanceof Iterable)) {
      return false;
    }
    for (final Object value : (Iterable<?>) values) {
      if (Objects.equals(attribute, value)
          || (attribute instanceof Number || value instanceof Number)
              && compare(attribute, value, (first, second) -> first == second)
          || String.valueOf(attribute).equals(String.valueOf(value))) {
        return true;
      }
    }
    return false;
  }

  private static boolean compare(
      final Object first, final Object second, final NumberPredicate predicate) {
    return predicate.test(number(first), number(second));
  }

  private static double number(final Object value) {
    return value instanceof Number
        ? ((Number) value).doubleValue()
        : Double.parseDouble(String.valueOf(value));
  }

  private static boolean matchesShard(final Shard shard, final String targetingKey) {
    if (shard.totalShards <= 0 || shard.ranges == null) {
      return false;
    }
    final String input = shard.salt + "-" + targetingKey;
    final byte[] digest;
    try {
      digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 algorithm not available", e);
    }
    long firstFourBytes = 0;
    for (int i = 0; i < 4; i++) {
      firstFourBytes = (firstFourBytes << 8) | (digest[i] & 0xffL);
    }
    final int assigned = (int) (firstFourBytes % shard.totalShards);
    for (final ShardRange range : shard.ranges) {
      if (assigned >= range.start && assigned < range.end) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCompatible(final ValueKind target, final ValueType type) {
    if (type == null) {
      return true;
    }
    switch (type) {
      case BOOLEAN:
        return target == ValueKind.BOOLEAN;
      case STRING:
        return target == ValueKind.STRING;
      case INTEGER:
        return target == ValueKind.INTEGER;
      case NUMERIC:
        return target == ValueKind.DOUBLE;
      case JSON:
        return target == ValueKind.OBJECT;
      default:
        return false;
    }
  }

  private static String typeName(final Flag flag) {
    return flag.variationType == null ? null : flag.variationType.name();
  }

  private static boolean isEmpty(final List<?> value) {
    return value == null || value.isEmpty();
  }

  @FunctionalInterface
  private interface NumberPredicate {
    boolean test(double first, double second);
  }
}
