package datadog.trace.api.openfeature.evaluator;

import static java.util.Arrays.asList;

import datadog.trace.api.openfeature.config.ufc.v1.Allocation;
import datadog.trace.api.openfeature.config.ufc.v1.ConditionConfiguration;
import datadog.trace.api.openfeature.config.ufc.v1.ConditionOperator;
import datadog.trace.api.openfeature.config.ufc.v1.Flag;
import datadog.trace.api.openfeature.config.ufc.v1.Rule;
import datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration;
import datadog.trace.api.openfeature.config.ufc.v1.Shard;
import datadog.trace.api.openfeature.config.ufc.v1.ShardRange;
import datadog.trace.api.openfeature.config.ufc.v1.Split;
import datadog.trace.api.openfeature.config.ufc.v1.Variant;
import datadog.trace.api.openfeature.exposure.ExposureListener;
import datadog.trace.api.openfeature.exposure.dto.ExposureEvent;
import datadog.trace.api.openfeature.exposure.dto.Subject;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.AbstractMap;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlagEvaluatorImpl implements FeatureFlagEvaluator {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagEvaluatorImpl.class);
  private static final List<DateTimeFormatter> DATE_FORMATTERS =
      asList(
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC),
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC));
  private static final Set<Class<?>> SUPPORTED_RESOLUTION_TYPES =
      new HashSet<>(asList(String.class, Boolean.class, Integer.class, Double.class, Value.class));

  private final AtomicReference<ServerConfiguration> configuration = new AtomicReference<>();
  private final ExposureListener exposureListener;

  public FeatureFlagEvaluatorImpl(final ExposureListener exposureListener) {
    this.exposureListener = exposureListener;
  }

  @Override
  public void onConfiguration(final ServerConfiguration config) {
    configuration.set(config);
  }

  @Override
  public <T> ProviderEvaluation<T> evaluate(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    try {
      final ServerConfiguration config = configuration.get();
      if (config == null) {
        return ProviderEvaluation.<T>builder()
            .value(defaultValue)
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.PROVIDER_NOT_READY)
            .build();
      }

      if (context == null) {
        return ProviderEvaluation.<T>builder()
            .value(defaultValue)
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.INVALID_CONTEXT)
            .build();
      }

      if (context.getTargetingKey() == null) {
        return ProviderEvaluation.<T>builder()
            .value(defaultValue)
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.TARGETING_KEY_MISSING)
            .build();
      }

      final Flag flag = config.flags.get(key);
      if (flag == null) {
        return ProviderEvaluation.<T>builder()
            .value(defaultValue)
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.FLAG_NOT_FOUND)
            .build();
      }

      if (!flag.enabled) {
        return ProviderEvaluation.<T>builder()
            .value(defaultValue)
            .reason(Reason.DISABLED.name())
            .build();
      }

      if (isEmpty(flag.allocations)) {
        return ProviderEvaluation.<T>builder()
            .value(defaultValue)
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.GENERAL)
            .errorMessage("Missing allocations for flag " + flag.key)
            .build();
      }

      final Date now = new Date();
      final String targetingKey = context.getTargetingKey();

      for (final Allocation allocation : flag.allocations) {
        if (!isAllocationActive(allocation, now)) {
          continue;
        }

        if (!isEmpty(allocation.rules)) {
          if (!evaluateRules(allocation.rules, context)) {
            continue;
          }
        }

        if (!isEmpty(allocation.splits)) {
          for (final Split split : allocation.splits) {
            if (isEmpty(split.shards)) {
              return resolveVariant(
                  target, key, defaultValue, flag, split.variationKey, allocation, context);
            } else {
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
                    target, key, defaultValue, flag, split.variationKey, allocation, context);
              }
            }
          }
        }
      }

      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(Reason.DEFAULT.name())
          .build();
    } catch (final NumberFormatException e) {
      LOGGER.debug("Evaluation failed for key {}", key, e);
      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(Reason.ERROR.name())
          .errorCode(ErrorCode.TYPE_MISMATCH)
          .build();
    } catch (final Exception e) {
      LOGGER.debug("Evaluation failed for key {}", key, e);
      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(Reason.ERROR.name())
          .errorCode(ErrorCode.GENERAL)
          .errorMessage(e.getMessage())
          .build();
    }
  }

  private static boolean isEmpty(final List<?> list) {
    return list == null || list.isEmpty();
  }

  private static boolean isAllocationActive(final Allocation allocation, final Date now) {
    final Date startDate = parseDate(allocation.startAt);
    if (startDate != null && now.before(startDate)) {
      return false;
    }

    final Date endDate = parseDate(allocation.endAt);
    if (endDate != null && now.after(endDate)) {
      return false;
    }

    return true;
  }

  private static boolean evaluateRules(final List<Rule> rules, final EvaluationContext context) {
    for (final Rule rule : rules) {
      if (isEmpty(rule.conditions)) {
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

  private static boolean evaluateCondition(
      final ConditionConfiguration condition, final EvaluationContext context) {
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

  private static boolean matchesRegex(final Object attributeValue, final Object conditionValue) {
    try {
      final Pattern pattern = Pattern.compile(String.valueOf(conditionValue));
      return pattern.matcher(String.valueOf(attributeValue)).find();
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isOneOf(final Object attributeValue, final Object conditionValue) {
    if (!(conditionValue instanceof Iterable)) {
      return false;
    }
    for (final Object value : (Iterable<?>) conditionValue) {
      if (valuesEqual(attributeValue, value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean valuesEqual(final Object a, final Object b) {
    if (Objects.equals(a, b)) {
      return true;
    }

    if (a instanceof Number || b instanceof Number) {
      return compareNumber(a, b, (first, second) -> first == second);
    }

    return String.valueOf(a).equals(String.valueOf(b));
  }

  private static boolean compareNumber(
      final Object attributeValue, final Object conditionValue, NumberComparator comparator) {
    final double a = mapValue(Double.class, attributeValue);
    final double b = mapValue(Double.class, conditionValue);
    return comparator.compare(a, b);
  }

  private static boolean matchesShard(final Shard shard, final String targetingKey) {
    final int assignedShard = getShard(shard.salt, targetingKey, shard.totalShards);
    for (final ShardRange range : shard.ranges) {
      if (assignedShard >= range.start && assignedShard < range.end) {
        return true;
      }
    }
    return false;
  }

  private static int getShard(final String salt, final String targetingKey, final int totalShards) {
    final String hashKey = salt + "-" + targetingKey;
    final String md5Hash = getMD5Hash(hashKey);
    final String first8Chars = md5Hash.substring(0, Math.min(8, md5Hash.length()));
    final long intFromHash = Long.parseLong(first8Chars, 16);
    return (int) (intFromHash % totalShards);
  }

  private static String getMD5Hash(final String input) {
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

  private <T> ProviderEvaluation<T> resolveVariant(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final Flag flag,
      final String variationKey,
      final Allocation allocation,
      final EvaluationContext context) {
    final Variant variant = flag.variations.get(variationKey);
    if (variant == null) {
      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(Reason.ERROR.name())
          .errorCode(ErrorCode.GENERAL)
          .errorMessage("Variant not found for: " + variationKey)
          .build();
    }

    final ImmutableMetadata.ImmutableMetadataBuilder metadataBuilder =
        ImmutableMetadata.builder()
            .addString("flagKey", flag.key)
            .addString("variationType", flag.variationType.name())
            .addString("allocationKey", allocation.key);
    final ProviderEvaluation<T> result =
        ProviderEvaluation.<T>builder()
            .value(mapValue(target, variant.value))
            .reason(Reason.TARGETING_MATCH.name())
            .variant(variant.key)
            .flagMetadata(metadataBuilder.build())
            .build();
    final boolean doLog = allocation.doLog != null && allocation.doLog;
    if (doLog) {
      dispatchExposure(key, result, context);
    }
    return result;
  }

  static Date parseDate(final String dateString) {
    if (dateString == null) {
      return null;
    }
    for (final DateTimeFormatter formatter : DATE_FORMATTERS) {
      try {
        final TemporalAccessor temporalAccessor = formatter.parse(dateString);
        final Instant instant = Instant.from(temporalAccessor);
        return Date.from(instant);
      } catch (DateTimeParseException e) {
        // ignore it
      }
    }
    return null;
  }

  private static Object resolveAttribute(final String name, final EvaluationContext context) {
    // Special handling for "id" attribute: if not explicitly provided, use targeting key
    if ("id".equals(name) && !context.keySet().contains(name)) {
      return context.getTargetingKey();
    }
    final Value resolved = context.getValue(name);
    return context.convertValue(resolved);
  }

  @SuppressWarnings("unchecked")
  static <T> T mapValue(final Class<T> target, final Object value) {
    if (value == null) {
      return null;
    }
    if (!SUPPORTED_RESOLUTION_TYPES.contains(target)) {
      throw new IllegalArgumentException("Type not supported: " + target);
    }
    if (target.isInstance(value)) {
      return target.cast(value);
    }
    if (target == String.class) {
      return (T) String.valueOf(value);
    }
    if (target == Boolean.class) {
      if (value instanceof Number) {
        return (T) (Boolean) (parseDouble(value) != 0);
      }
      return (T) Boolean.valueOf(value.toString());
    }
    if (target == Integer.class) {
      final Double number = parseDouble(value);
      return (T) (Integer) number.intValue();
    }
    if (target == Double.class) {
      final Double number = parseDouble(value);
      return (T) number;
    }
    return (T) Value.objectToValue(value);
  }

  private static Double parseDouble(final Object value) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  private <T> void dispatchExposure(
      final String flag, final ProviderEvaluation<T> evaluation, final EvaluationContext context) {
    final String allocationKey = allocationKey(evaluation);
    final String variantKey = evaluation.getVariant();
    if (allocationKey == null || variantKey == null) {
      return;
    }
    final ExposureEvent event =
        new ExposureEvent(
            System.currentTimeMillis(),
            new datadog.trace.api.openfeature.exposure.dto.Allocation(allocationKey),
            new datadog.trace.api.openfeature.exposure.dto.Flag(flag),
            new datadog.trace.api.openfeature.exposure.dto.Variant(variantKey),
            new Subject(context.getTargetingKey(), flattenContext(context)));
    exposureListener.onExposure(event);
  }

  private static <T> String allocationKey(final ProviderEvaluation<T> resolution) {
    final ImmutableMetadata meta = resolution.getFlagMetadata();
    return meta == null ? null : meta.getString("allocationKey");
  }

  static AbstractMap<String, Object> flattenContext(final EvaluationContext context) {
    final Set<String> keys = context.keySet();
    final HashMap<String, Object> result = new HashMap<>();
    final Set<Value> seen = new HashSet<>();
    for (final String key : keys) {
      final Deque<FlattenEntry> deque = new LinkedList<>();
      deque.push(new FlattenEntry(key, context.getValue(key)));
      while (!deque.isEmpty()) {
        final FlattenEntry entry = deque.pop();
        final Value value = entry.value;
        if (value == null || seen.add(value)) {
          if (value == null) {
            result.put(entry.key, null);
          } else if (value.isList()) {
            final List<Value> list = value.asList();
            for (int i = 0; i < list.size(); i++) {
              deque.push(new FlattenEntry(entry.key + "[" + i + "]", list.get(i)));
            }
          } else if (value.isStructure()) {
            final Structure structure = value.asStructure();
            for (final String property : structure.keySet()) {
              deque.push(
                  new FlattenEntry(entry.key + "." + property, structure.getValue(property)));
            }
          } else {
            result.put(entry.key, context.convertValue(value));
          }
        }
      }
    }
    return result;
  }

  @FunctionalInterface
  private interface NumberComparator {
    boolean compare(double a, double b);
  }

  private static class FlattenEntry {
    private final String key;
    private final Value value;

    private FlattenEntry(final String key, final Value value) {
      this.key = key;
      this.value = value;
    }
  }
}
