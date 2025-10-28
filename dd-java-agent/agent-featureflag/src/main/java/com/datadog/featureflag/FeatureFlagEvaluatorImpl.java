package com.datadog.featureflag;

import com.datadog.featureflag.ufc.v1.Allocation;
import com.datadog.featureflag.ufc.v1.ConditionConfiguration;
import com.datadog.featureflag.ufc.v1.ConditionOperator;
import com.datadog.featureflag.ufc.v1.Flag;
import com.datadog.featureflag.ufc.v1.Rule;
import com.datadog.featureflag.ufc.v1.ServerConfiguration;
import com.datadog.featureflag.ufc.v1.Shard;
import com.datadog.featureflag.ufc.v1.ShardRange;
import com.datadog.featureflag.ufc.v1.Split;
import com.datadog.featureflag.ufc.v1.Variant;
import datadog.trace.api.featureflag.FeatureFlagEvaluator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class FeatureFlagEvaluatorImpl
    implements FeatureFlagEvaluator, Consumer<ServerConfiguration> {

  private final Set<Listener> listeners = Collections.newSetFromMap(new WeakHashMap<>());
  private final AtomicReference<ServerConfiguration> configuration = new AtomicReference<>();

  @Override
  public Resolution<Boolean> evaluate(
      final String key, final Boolean defaultValue, final Context context) {
    return evaluateInternal(key, defaultValue, context);
  }

  @Override
  public Resolution<Integer> evaluate(
      final String key, final Integer defaultValue, final Context context) {
    return evaluateInternal(key, defaultValue, context);
  }

  @Override
  public Resolution<Double> evaluate(
      final String key, final Double defaultValue, final Context context) {
    return evaluateInternal(key, defaultValue, context);
  }

  @Override
  public Resolution<String> evaluate(
      final String key, final String defaultValue, final Context context) {
    return evaluateInternal(key, defaultValue, context);
  }

  @Override
  public Resolution<Object> evaluate(
      final String key, final Object defaultValue, final Context context) {
    return evaluateInternal(key, defaultValue, context);
  }

  private <T> Resolution<T> evaluateInternal(
      final String key, final T defaultValue, final Context context) {
    final ServerConfiguration config = configuration.get();

    if (config == null) {
      // TODO: log warning and telemetry
      return Resolution.notInitialized(defaultValue);
    }

    if (context == null || context.getTargetingKey() == null) {
      // TODO: log error and telemetry
      return Resolution.error(defaultValue);
    }

    final Flag flag = config.flags.get(key);
    if (flag == null) {
      // TODO: log warning and telemetry
      return Resolution.defaultResolution(defaultValue);
    }

    if (!flag.enabled) {
      // TODO: log warning and telemetry
      return new Resolution<>(defaultValue).setReason(ResolutionReason.DISABLED);
    }

    if (flag.allocations == null || flag.allocations.isEmpty()) {
      // TODO: log warning and telemetry
      return Resolution.defaultResolution(defaultValue);
    }

    final Date now = new Date();
    final String targetingKey = context.getTargetingKey();

    for (final Allocation allocation : flag.allocations) {
      if (!isAllocationActive(allocation, now)) {
        continue;
      }

      if (allocation.rules != null && !allocation.rules.isEmpty()) {
        if (!evaluateRules(allocation.rules, context)) {
          continue;
        }
      }

      if (allocation.splits != null) {
        for (final Split split : allocation.splits) {
          if (split.shards != null) {
            if (split.shards.isEmpty()) {
              return resolveVariant(flag, split.variationKey, allocation);
            }

            // To match a split, subject must match ALL underlying shards
            boolean allShardsMatch = true;
            for (final Shard shard : split.shards) {
              if (!matchesShard(shard, targetingKey)) {
                allShardsMatch = false;
                break;
              }
            }
            if (allShardsMatch) {
              return resolveVariant(flag, split.variationKey, allocation);
            }
          }
        }
      }
    }

    return Resolution.defaultResolution(defaultValue);
  }

  private boolean isAllocationActive(final Allocation allocation, final Date now) {
    if (allocation.startAt != null) {
      final Date startDate = parseDate(allocation.startAt);
      if (startDate != null && now.before(startDate)) {
        return false;
      }
    }

    if (allocation.endAt != null) {
      final Date endDate = parseDate(allocation.endAt);
      if (endDate != null && now.after(endDate)) {
        return false;
      }
    }

    return true;
  }

  private boolean evaluateRules(final List<Rule> rules, final Context context) {
    for (final Rule rule : rules) {
      if (rule.conditions == null || rule.conditions.isEmpty()) {
        continue;
      }

      boolean allConditionsMatch = true;
      for (final ConditionConfiguration condition : rule.conditions) {
        if (!evaluateCondition(condition, context)) {
          allConditionsMatch = false;
          break;
        }
      }

      if (allConditionsMatch) {
        return true;
      }
    }
    return false;
  }

  private boolean evaluateCondition(final ConditionConfiguration condition, final Context context) {
    if (condition.operator == ConditionOperator.IS_NULL) {
      final Object value = resolveAttribute(condition.attribute, context);
      boolean isNull = value == null;
      // condition.value determines if we're checking for null (true) or not null (false)
      boolean expectedNull = condition.value instanceof Boolean ? (Boolean) condition.value : true;
      return isNull == expectedNull;
    }

    final Object attributeValue = resolveAttribute(condition.attribute, context);
    if (attributeValue == null) {
      return false;
    }

    switch (condition.operator) {
      case MATCHES:
        return matchesRegex(attributeValue, condition.value);
      case NOT_MATCHES:
        return !matchesRegex(attributeValue, condition.value);
      case ONE_OF:
        return isOneOf(attributeValue, condition.value);
      case NOT_ONE_OF:
        return !isOneOf(attributeValue, condition.value);
      case GTE:
        return compareNumber(attributeValue, condition.value, (a, b) -> a >= b);
      case GT:
        return compareNumber(attributeValue, condition.value, (a, b) -> a > b);
      case LTE:
        return compareNumber(attributeValue, condition.value, (a, b) -> a <= b);
      case LT:
        return compareNumber(attributeValue, condition.value, (a, b) -> a < b);
      default:
        return false;
    }
  }

  private boolean matchesRegex(final Object attributeValue, final Object conditionValue) {
    if (!(conditionValue instanceof String)) {
      return false;
    }
    try {
      final Pattern pattern = Pattern.compile((String) conditionValue);
      return pattern.matcher(String.valueOf(attributeValue)).find();
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isOneOf(final Object attributeValue, final Object conditionValue) {
    if (!(conditionValue instanceof Collection)) {
      return false;
    }
    final Collection<?> values = (Collection<?>) conditionValue;
    for (final Object value : values) {
      if (valuesEqual(attributeValue, value)) {
        return true;
      }
    }
    return false;
  }

  private boolean valuesEqual(final Object a, final Object b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }

    if (a instanceof Number || b instanceof Number) {
      return numbersEqual(a, b);
    }

    return String.valueOf(a).equals(String.valueOf(b));
  }

  private boolean numbersEqual(final Object a, final Object b) {
    try {
      return toDouble(a) == toDouble(b);
    } catch (Exception e) {
      return String.valueOf(a).equals(String.valueOf(b));
    }
  }

  private boolean compareNumber(
      final Object attributeValue, final Object conditionValue, NumberComparator comparator) {
    try {
      final double a = toDouble(attributeValue);
      final double b = toDouble(conditionValue);
      return comparator.compare(a, b);
    } catch (Exception e) {
      return false;
    }
  }

  private double toDouble(final Object value) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  private boolean matchesShard(final Shard shard, final String targetingKey) {
    final int assignedShard = getShard(shard.salt, targetingKey, shard.totalShards);
    for (final ShardRange range : shard.ranges) {
      if (assignedShard >= range.start && assignedShard < range.end) {
        return true;
      }
    }
    return false;
  }

  private int getShard(final String salt, final String targetingKey, final int totalShards) {
    final String hashKey = salt + "-" + targetingKey;
    final String md5Hash = getMD5Hash(hashKey);
    final String first8Chars = md5Hash.substring(0, Math.min(8, md5Hash.length()));
    final long intFromHash = Long.parseLong(first8Chars, 16);
    return (int) (intFromHash % totalShards);
  }

  private String getMD5Hash(final String input) {
    try {
      final MessageDigest md = MessageDigest.getInstance("MD5");
      final byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hexString = new StringBuilder();
      for (byte b : hashBytes) {
        final String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("MD5 algorithm not available", e);
    }
  }

  @SuppressWarnings("unchecked")
  private <T> Resolution<T> resolveVariant(
      final Flag flag, final String variationKey, final Allocation allocation) {
    final Variant variant = flag.variations.get(variationKey);
    if (variant == null) {
      return Resolution.error((T) null);
    }

    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("flagKey", flag.key);
    metadata.put("variationType", flag.variationType.name());
    if (allocation.doLog != null && allocation.doLog) {
      metadata.put("allocationKey", allocation.key);
    }

    return Resolution.targetingMatch((T) variant.value)
        .setVariant(variant.key)
        .setFlagMetadata(metadata);
  }

  private Date parseDate(final String dateString) {
    if (dateString == null) {
      return null;
    }
    try {
      final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
      sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
      return sdf.parse(dateString);
    } catch (ParseException e) {
      try {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.parse(dateString);
      } catch (ParseException e2) {
        return null;
      }
    }
  }

  @FunctionalInterface
  private interface NumberComparator {
    boolean compare(double a, double b);
  }

  private Object resolveAttribute(final String name, final Context context) {
    // Special handling for "id" attribute: if not explicitly provided, use targeting key
    if ("id".equals(name) && !context.keySet().contains(name)) {
      return context.getTargetingKey();
    }
    return context.getValue(name);
  }

  @Override
  public void addListener(final Listener listener) {
    if (configuration.get() != null) {
      listener.onInitialized();
    }
    synchronized (listeners) {
      this.listeners.add(listener);
    }
  }

  @Override
  public void accept(final ServerConfiguration serverConfiguration) {
    final boolean init = configuration.getAndSet(serverConfiguration) == null;
    synchronized (listeners) {
      if (init) {
        listeners.forEach(Listener::onInitialized);
      } else {
        listeners.forEach(Listener::onConfigurationChanged);
      }
    }
  }
}
