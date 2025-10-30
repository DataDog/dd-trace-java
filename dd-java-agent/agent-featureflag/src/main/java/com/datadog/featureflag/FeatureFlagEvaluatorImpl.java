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
import datadog.trace.api.featureflag.FeatureFlagConfiguration;
import datadog.trace.api.featureflag.FeatureFlagEvaluator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlagEvaluatorImpl
    implements FeatureFlagEvaluator, Consumer<FeatureFlagConfiguration> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagEvaluatorImpl.class);
  private final AtomicReference<ServerConfiguration> configuration = new AtomicReference<>();

  @Override
  public void accept(final FeatureFlagConfiguration serverConfiguration) {
    configuration.getAndSet((ServerConfiguration) serverConfiguration);
  }

  @Override
  public Resolution<Boolean> evaluate(
      final String key, final Boolean defaultValue, final Context context) {
    return evaluateInternal(Boolean.class, key, defaultValue, context);
  }

  @Override
  public Resolution<Integer> evaluate(
      final String key, final Integer defaultValue, final Context context) {
    return evaluateInternal(Integer.class, key, defaultValue, context);
  }

  @Override
  public Resolution<Double> evaluate(
      final String key, final Double defaultValue, final Context context) {
    return evaluateInternal(Double.class, key, defaultValue, context);
  }

  @Override
  public Resolution<String> evaluate(
      final String key, final String defaultValue, final Context context) {
    return evaluateInternal(String.class, key, defaultValue, context);
  }

  @Override
  public Resolution<Object> evaluate(
      final String key, final Object defaultValue, final Context context) {
    return evaluateInternal(Object.class, key, defaultValue, context);
  }

  private <T> Resolution<T> evaluateInternal(
      final Class<T> target, final String key, final T defaultValue, final Context context) {
    try {
      final ServerConfiguration config = configuration.get();
      if (config == null) {
        return Resolution.providerNotReady(key, defaultValue);
      }

      if (context == null) {
        return Resolution.invalidContext(key, defaultValue);
      }

      if (context.getTargetingKey() == null) {
        return Resolution.targetingKeyMissing(key, defaultValue);
      }

      final Flag flag = config.flags.get(key);
      if (flag == null) {
        return Resolution.flagNotFound(key, defaultValue);
      }

      if (!flag.enabled) {
        return Resolution.disabled(key, defaultValue);
      }

      if (flag.allocations == null || flag.allocations.isEmpty()) {
        return Resolution.generalError(
            key, defaultValue, "Missing allocations in flag " + flag.key);
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
                return resolveVariant(
                    target, key, defaultValue, flag, split.variationKey, allocation);
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
                return resolveVariant(
                    target, key, defaultValue, flag, split.variationKey, allocation);
              }
            }
          }
        }
      }

      return Resolution.defaultResolution(key, defaultValue);
    } catch (final NumberFormatException e) {
      LOGGER.debug("Evaluation failed for key {}", key, e);
      return Resolution.typeMissmatch(key, defaultValue, e.getMessage());
    } catch (final Exception e) {
      LOGGER.debug("Evaluation failed for key {}", key, e);
      return Resolution.generalError(key, defaultValue, e.getMessage());
    }
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
    if (Objects.equals(a, b)) {
      return true;
    }

    if (a instanceof Number || b instanceof Number) {
      return compareNumber(a, b, (first, second) -> first == second);
    }

    return String.valueOf(a).equals(String.valueOf(b));
  }

  private boolean compareNumber(
      final Object attributeValue, final Object conditionValue, NumberComparator comparator) {
    final double a = mapValue(Double.class, attributeValue);
    final double b = mapValue(Double.class, conditionValue);
    return comparator.compare(a, b);
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

  private <T> Resolution<T> resolveVariant(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final Flag flag,
      final String variationKey,
      final Allocation allocation) {
    final Variant variant = flag.variations.get(variationKey);
    if (variant == null) {
      return Resolution.generalError(key, defaultValue, "Variation not found for: " + variationKey);
    }

    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("flagKey", flag.key);
    metadata.put("variationType", flag.variationType.name());
    if (allocation.doLog != null && allocation.doLog) {
      metadata.put("allocationKey", allocation.key);
    }

    return Resolution.targetingMatch(key, mapValue(target, variant.value))
        .setVariant(variant.key)
        .setFlagMetadata(metadata);
  }

  @SuppressWarnings("unchecked")
  private <T> T mapValue(final Class<T> target, final Object value) {
    if (value == null) {
      return null;
    }
    if (target.isInstance(value)) {
      return target.cast(value);
    }
    if (target == String.class) {
      return (T) String.valueOf(value);
    }
    if (target == Boolean.class) {
      return (T) Boolean.valueOf(value.toString());
    }
    if (target == Integer.class) {
      return (T) ((Integer) Double.valueOf(value.toString()).intValue());
    }
    if (target == Double.class) {
      return (T) Double.valueOf(value.toString());
    }
    return (T) value;
  }

  private Date parseDate(final String dateString) {
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
}
