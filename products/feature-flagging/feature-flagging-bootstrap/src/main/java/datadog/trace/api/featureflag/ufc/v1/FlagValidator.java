package datadog.trace.api.featureflag.ufc.v1;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class FlagValidator {
  private static final String MAX_UNSIGNED_LONG = "18446744073709551615";

  public static void validateJson(final Object rawFlag) {
    if (!(rawFlag instanceof Map)) {
      return;
    }
    final Object allocations = ((Map<?, ?>) rawFlag).get("allocations");
    if (!(allocations instanceof List)) {
      return;
    }
    for (final Object allocation : (List<?>) allocations) {
      if (!(allocation instanceof Map)) {
        continue;
      }
      final Object splits = ((Map<?, ?>) allocation).get("splits");
      if (!(splits instanceof List)) {
        continue;
      }
      for (final Object split : (List<?>) splits) {
        validateJsonSplit(split);
      }
    }
  }

  public static void validate(final Flag flag) {
    if (flag == null || flag.variationType == null || flag.variations == null) {
      throw new IllegalArgumentException("flag is incomplete");
    }
    for (final Map.Entry<String, Variant> entry : flag.variations.entrySet()) {
      final Variant variant = entry.getValue();
      if (variant == null || !matchesType(variant.value, flag.variationType)) {
        throw new IllegalArgumentException("variation has an invalid value: " + entry.getKey());
      }
    }
    if (flag.allocations == null) {
      return;
    }
    for (final Allocation allocation : flag.allocations) {
      if (allocation == null || allocation.splits == null) {
        throw new IllegalArgumentException("allocation is incomplete");
      }
      for (final Split split : allocation.splits) {
        if (split == null
            || split.shards == null
            || !flag.variations.containsKey(split.variationKey)) {
          throw new IllegalArgumentException("split is incomplete");
        }
        for (final Shard shard : split.shards) {
          if (shard == null || shard.totalShards <= 0 || shard.ranges == null) {
            throw new IllegalArgumentException("shard is incomplete");
          }
          for (final ShardRange range : shard.ranges) {
            if (range == null
                || range.start < 0
                || range.start >= range.end
                || range.end > shard.totalShards) {
              throw new IllegalArgumentException("shard range is invalid");
            }
          }
        }
      }
      if (allocation.rules == null) {
        continue;
      }
      for (final Rule rule : allocation.rules) {
        if (rule == null || rule.conditions == null) {
          throw new IllegalArgumentException("rule is incomplete");
        }
        for (final ConditionConfiguration condition : rule.conditions) {
          validateCondition(condition);
        }
      }
    }
  }

  private static void validateCondition(final ConditionConfiguration condition) {
    if (condition == null || condition.operator == null) {
      throw new IllegalArgumentException("condition is incomplete");
    }
    switch (condition.operator) {
      case MATCHES:
      case NOT_MATCHES:
        if (!(condition.value instanceof String)) {
          throw new IllegalArgumentException("regex comparand must be a string");
        }
        compileRegex((String) condition.value);
        return;
      case LT:
      case LTE:
      case GT:
      case GTE:
        if (!(condition.value instanceof Number)) {
          throw new IllegalArgumentException("numeric comparand must be a number");
        }
        return;
      case ONE_OF:
      case NOT_ONE_OF:
        if (!(condition.value instanceof List)) {
          throw new IllegalArgumentException("membership comparand must be a list");
        }
        for (final Object value : (List<?>) condition.value) {
          if (!(value instanceof String)) {
            throw new IllegalArgumentException("membership comparand must contain strings");
          }
        }
        return;
      case IS_NULL:
        if (!(condition.value instanceof Boolean)) {
          throw new IllegalArgumentException("null comparand must be boolean");
        }
        return;
      case SEMVER_EQ:
      case SEMVER_NEQ:
      case SEMVER_LT:
      case SEMVER_LTE:
      case SEMVER_GT:
      case SEMVER_GTE:
        SemanticVersion.parse(condition.value);
        return;
      default:
        throw new IllegalArgumentException("unknown condition operator");
    }
  }

  private static boolean matchesType(final Object value, final ValueType type) {
    switch (type) {
      case BOOLEAN:
        return value instanceof Boolean;
      case STRING:
        return value instanceof String;
      case INTEGER:
        return value instanceof Number && ((Number) value).doubleValue() % 1 == 0;
      case NUMERIC:
        return value instanceof Number;
      case JSON:
        return true;
      default:
        return false;
    }
  }

  public static Pattern compileRegex(final String expression) {
    return Pattern.compile(expression.replace("[:alnum:]", "\\p{Alnum}"));
  }

  private static void validateJsonSplit(final Object rawSplit) {
    if (!(rawSplit instanceof Map)) {
      return;
    }
    final Object shards = ((Map<?, ?>) rawSplit).get("shards");
    if (!(shards instanceof List)) {
      return;
    }
    for (final Object shard : (List<?>) shards) {
      if (!(shard instanceof Map)) {
        continue;
      }
      final Map<?, ?> shardMap = (Map<?, ?>) shard;
      validateJavaInteger(shardMap.get("totalShards"), true, "totalShards");
      final Object ranges = shardMap.get("ranges");
      if (!(ranges instanceof List)) {
        continue;
      }
      for (final Object range : (List<?>) ranges) {
        if (range instanceof Map) {
          validateJavaInteger(((Map<?, ?>) range).get("start"), false, "range start");
          validateJavaInteger(((Map<?, ?>) range).get("end"), false, "range end");
        }
      }
    }
  }

  private static void validateJavaInteger(
      final Object value, final boolean positive, final String field) {
    if (!(value instanceof Number)) {
      return;
    }
    final double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number)
        || number != Math.rint(number)
        || positive && number <= 0
        || !positive && number < 0
        || number > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(field + " is outside the supported integer range");
    }
  }

  static void validateSemanticVersionComponent(final String value) {
    if (value.length() > MAX_UNSIGNED_LONG.length()
        || value.length() == MAX_UNSIGNED_LONG.length() && value.compareTo(MAX_UNSIGNED_LONG) > 0) {
      throw new IllegalArgumentException("semantic version component is too large");
    }
  }

  private FlagValidator() {}
}
