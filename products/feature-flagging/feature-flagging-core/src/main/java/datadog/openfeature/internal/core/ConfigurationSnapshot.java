package datadog.openfeature.internal.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable UFC configuration used by the provider-owned evaluator. */
public final class ConfigurationSnapshot {

  public final String createdAt;
  public final String format;
  public final String environmentName;
  public final Map<String, Flag> flags;

  ConfigurationSnapshot(
      final String createdAt,
      final String format,
      final String environmentName,
      final Map<String, Flag> flags) {
    this.createdAt = createdAt;
    this.format = format;
    this.environmentName = environmentName;
    this.flags = Collections.unmodifiableMap(flags);
  }

  public enum ValueType {
    BOOLEAN,
    INTEGER,
    NUMERIC,
    STRING,
    JSON
  }

  public enum ConditionOperator {
    LT,
    LTE,
    GT,
    GTE,
    MATCHES,
    NOT_MATCHES,
    ONE_OF,
    NOT_ONE_OF,
    IS_NULL
  }

  public static final class Flag {
    public final String key;
    public final boolean enabled;
    public final ValueType variationType;
    public final Map<String, Variant> variations;
    public final List<Allocation> allocations;

    Flag(
        final String key,
        final boolean enabled,
        final ValueType variationType,
        final Map<String, Variant> variations,
        final List<Allocation> allocations) {
      this.key = key;
      this.enabled = enabled;
      this.variationType = variationType;
      this.variations = Collections.unmodifiableMap(variations);
      this.allocations = allocations == null ? null : Collections.unmodifiableList(allocations);
    }
  }

  public static final class Variant {
    public final String key;
    public final Object value;

    Variant(final String key, final Object value) {
      this.key = key;
      this.value = value;
    }
  }

  public static final class Allocation {
    public final String key;
    public final List<Rule> rules;
    public final Long startAtMillis;
    public final Long endAtMillis;
    public final List<Split> splits;
    public final boolean doLog;

    Allocation(
        final String key,
        final List<Rule> rules,
        final Long startAtMillis,
        final Long endAtMillis,
        final List<Split> splits,
        final boolean doLog) {
      this.key = key;
      this.rules = rules == null ? null : Collections.unmodifiableList(rules);
      this.startAtMillis = startAtMillis;
      this.endAtMillis = endAtMillis;
      this.splits = splits == null ? null : Collections.unmodifiableList(splits);
      this.doLog = doLog;
    }
  }

  public static final class Rule {
    public final List<Condition> conditions;

    Rule(final List<Condition> conditions) {
      this.conditions = conditions == null ? null : Collections.unmodifiableList(conditions);
    }
  }

  public static final class Condition {
    public final ConditionOperator operator;
    public final String attribute;
    public final Object value;

    Condition(final ConditionOperator operator, final String attribute, final Object value) {
      this.operator = operator;
      this.attribute = attribute;
      this.value = value;
    }
  }

  public static final class Split {
    public final List<Shard> shards;
    public final String variationKey;
    public final Map<String, String> extraLogging;
    public final Integer serialId;

    Split(
        final List<Shard> shards,
        final String variationKey,
        final Map<String, String> extraLogging,
        final Integer serialId) {
      this.shards = shards == null ? null : Collections.unmodifiableList(shards);
      this.variationKey = variationKey;
      this.extraLogging = extraLogging == null ? null : Collections.unmodifiableMap(extraLogging);
      this.serialId = serialId;
    }
  }

  public static final class Shard {
    public final String salt;
    public final List<ShardRange> ranges;
    public final int totalShards;

    Shard(final String salt, final List<ShardRange> ranges, final int totalShards) {
      this.salt = salt;
      this.ranges = Collections.unmodifiableList(ranges);
      this.totalShards = totalShards;
    }
  }

  public static final class ShardRange {
    public final int start;
    public final int end;

    ShardRange(final int start, final int end) {
      this.start = start;
      this.end = end;
    }
  }
}
