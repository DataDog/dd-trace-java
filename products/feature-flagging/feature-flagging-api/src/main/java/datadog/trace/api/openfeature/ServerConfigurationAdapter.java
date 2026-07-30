package datadog.trace.api.openfeature;

import datadog.openfeature.internal.core.ConfigurationSnapshot;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import datadog.trace.api.featureflag.ufc.v1.ShardRange;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the agent-delivered UFC model to the shared evaluator model. */
final class ServerConfigurationAdapter {

  private ServerConfigurationAdapter() {}

  static ConfigurationSnapshot adapt(final ServerConfiguration source) {
    if (source == null) {
      return null;
    }
    final Map<String, ConfigurationSnapshot.Flag> flags = new LinkedHashMap<>();
    if (source.flags != null) {
      for (final Map.Entry<String, Flag> entry : source.flags.entrySet()) {
        flags.put(entry.getKey(), adapt(entry.getValue()));
      }
    }
    return new ConfigurationSnapshot(
        source.createdAt,
        source.format,
        source.environment == null ? null : source.environment.name,
        flags);
  }

  private static ConfigurationSnapshot.Flag adapt(final Flag source) {
    if (source == null) {
      return null;
    }
    final Map<String, ConfigurationSnapshot.Variant> variants = new LinkedHashMap<>();
    if (source.variations != null) {
      for (final Map.Entry<String, Variant> entry : source.variations.entrySet()) {
        final Variant variant = entry.getValue();
        variants.put(
            entry.getKey(),
            variant == null ? null : new ConfigurationSnapshot.Variant(variant.key, variant.value));
      }
    }
    return new ConfigurationSnapshot.Flag(
        source.key,
        source.enabled,
        source.variationType == null
            ? null
            : ConfigurationSnapshot.ValueType.valueOf(source.variationType.name()),
        variants,
        adaptAllocations(source.allocations));
  }

  private static List<ConfigurationSnapshot.Allocation> adaptAllocations(
      final List<Allocation> sources) {
    if (sources == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Allocation> result = new ArrayList<>(sources.size());
    for (final Allocation source : sources) {
      result.add(
          source == null
              ? null
              : new ConfigurationSnapshot.Allocation(
                  source.key,
                  adaptRules(source.rules),
                  source.startAt == null ? null : source.startAt.getTime(),
                  source.endAt == null ? null : source.endAt.getTime(),
                  adaptSplits(source.splits),
                  Boolean.TRUE.equals(source.doLog)));
    }
    return result;
  }

  private static List<ConfigurationSnapshot.Rule> adaptRules(final List<Rule> sources) {
    if (sources == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Rule> result = new ArrayList<>(sources.size());
    for (final Rule source : sources) {
      result.add(
          source == null
              ? null
              : new ConfigurationSnapshot.Rule(adaptConditions(source.conditions)));
    }
    return result;
  }

  private static List<ConfigurationSnapshot.Condition> adaptConditions(
      final List<ConditionConfiguration> sources) {
    if (sources == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Condition> result = new ArrayList<>(sources.size());
    for (final ConditionConfiguration source : sources) {
      result.add(
          source == null
              ? null
              : new ConfigurationSnapshot.Condition(
                  source.operator == null
                      ? null
                      : ConfigurationSnapshot.ConditionOperator.valueOf(source.operator.name()),
                  source.attribute,
                  source.value));
    }
    return result;
  }

  private static List<ConfigurationSnapshot.Split> adaptSplits(final List<Split> sources) {
    if (sources == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Split> result = new ArrayList<>(sources.size());
    for (final Split source : sources) {
      result.add(
          source == null
              ? null
              : new ConfigurationSnapshot.Split(
                  adaptShards(source.shards),
                  source.variationKey,
                  source.extraLogging == null ? null : new LinkedHashMap<>(source.extraLogging),
                  source.serialId));
    }
    return result;
  }

  private static List<ConfigurationSnapshot.Shard> adaptShards(final List<Shard> sources) {
    if (sources == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Shard> result = new ArrayList<>(sources.size());
    for (final Shard source : sources) {
      result.add(
          source == null
              ? null
              : new ConfigurationSnapshot.Shard(
                  source.salt, adaptRanges(source.ranges), source.totalShards));
    }
    return result;
  }

  private static List<ConfigurationSnapshot.ShardRange> adaptRanges(
      final List<ShardRange> sources) {
    if (sources == null) {
      return Collections.emptyList();
    }
    final List<ConfigurationSnapshot.ShardRange> result = new ArrayList<>(sources.size());
    for (final ShardRange source : sources) {
      if (source != null) {
        result.add(new ConfigurationSnapshot.ShardRange(source.start, source.end));
      }
    }
    return result;
  }
}
