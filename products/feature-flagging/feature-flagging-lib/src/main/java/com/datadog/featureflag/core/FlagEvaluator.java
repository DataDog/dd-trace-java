package com.datadog.featureflag.core;

import static java.util.Arrays.asList;

import com.datadog.featureflag.core.EvaluationResult.ErrorCode;
import com.datadog.featureflag.core.EvaluationResult.Reason;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ParsedSemver;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import datadog.trace.api.featureflag.ufc.v1.ShardRange;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Local evaluation over a single configuration snapshot. No transport or OpenFeature dependency.
 */
public final class FlagEvaluator {
  private static final Set<Class<?>> SUPPORTED_RESOLUTION_TYPES =
      new HashSet<>(asList(String.class, Boolean.class, Integer.class, Double.class, Object.class));
  private static final String METADATA_OBSERVE_FULL_EVALUATION_DATA =
      "observe_full_evaluation_data";
  private static final String METADATA_SPLIT_SERIAL_ID = "__dd_split_serial_id";
  private static final String METADATA_DO_LOG = "__dd_do_log";
  private final boolean spanEnrichmentEnabled;

  public FlagEvaluator(final boolean spanEnrichmentEnabled) {
    this.spanEnrichmentEnabled = spanEnrichmentEnabled;
  }

  public <T> EvaluationResult<T> evaluate(
      final ServerConfiguration config,
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    // Snapshot the config once and thread observeFullEvaluationData through every
    // EvaluationResult returned, so the hook's consent decision is pinned to this evaluation's
    // config and cannot drift on a concurrent Remote Config swap.
    // Boolean.TRUE.equals covers both null (privacy-preserving default) and Boolean.FALSE without
    // an NPE — the field is boxed so a malformed UFC message doesn't abort the whole parse.
    final boolean observeFullEvaluationData =
        config != null && Boolean.TRUE.equals(config.observeFullEvaluationData);
    try {
      if (config == null) {
        return error(defaultValue, ErrorCode.PROVIDER_NOT_READY, null, observeFullEvaluationData);
      }

      if (context == null) {
        return error(defaultValue, ErrorCode.INVALID_CONTEXT, null, observeFullEvaluationData);
      }

      final Flag flag = config.flags.get(key);
      if (flag == null) {
        if (config.invalidFlags != null && config.invalidFlags.containsKey(key)) {
          return error(
              defaultValue,
              ErrorCode.PARSE_ERROR,
              "invalid configuration for flag " + key,
              observeFullEvaluationData);
        }
        return error(defaultValue, ErrorCode.FLAG_NOT_FOUND, null, observeFullEvaluationData);
      }

      if (!flag.enabled) {
        return EvaluationResult.<T>builder()
            .value(defaultValue)
            .reason(Reason.DISABLED.name())
            .flagMetadata(consentMetadata(observeFullEvaluationData))
            .build();
      }

      if (flag.allocations == null) {
        return error(
            defaultValue,
            ErrorCode.GENERAL,
            "Missing allocations for flag " + key,
            observeFullEvaluationData);
      }

      final Instant now = Instant.now();
      final long evalTimestampMs = now.toEpochMilli();
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
                  target,
                  key,
                  defaultValue,
                  flag,
                  split.variationKey,
                  allocation,
                  split,
                  context,
                  evalTimestampMs,
                  observeFullEvaluationData);
            } else {
              if (targetingKey == null) {
                return error(
                    defaultValue, ErrorCode.TARGETING_KEY_MISSING, null, observeFullEvaluationData);
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
                    target,
                    key,
                    defaultValue,
                    flag,
                    split.variationKey,
                    allocation,
                    split,
                    context,
                    evalTimestampMs,
                    observeFullEvaluationData);
              }
            }
          }
        }
      }

      return EvaluationResult.<T>builder()
          .value(defaultValue)
          .reason(Reason.DEFAULT.name())
          .flagMetadata(consentMetadata(observeFullEvaluationData))
          .build();
    } catch (final PatternSyntaxException e) {
      return error(defaultValue, ErrorCode.PARSE_ERROR, e.getMessage(), observeFullEvaluationData);
    } catch (final NumberFormatException e) {
      return error(
          defaultValue, ErrorCode.TYPE_MISMATCH, e.getMessage(), observeFullEvaluationData);
    } catch (final Exception e) {
      return error(defaultValue, ErrorCode.GENERAL, e.getMessage(), observeFullEvaluationData);
    }
  }

  private static EvaluationMetadata consentMetadata(final boolean observeFullEvaluationData) {
    return EvaluationMetadata.builder()
        .addBoolean(METADATA_OBSERVE_FULL_EVALUATION_DATA, observeFullEvaluationData)
        .build();
  }

  private static <T> EvaluationResult<T> error(
      final T defaultValue,
      final ErrorCode code,
      final String errorMessage,
      final boolean observeFullEvaluationData) {
    // Under consent-off the errorMessage is dropped: exception messages from the outer catch blocks
    // (NumberFormatException, generic Exception) can echo raw evaluation-context values, so they
    // must never reach any consumer of EvaluationResult.getErrorMessage() — not just our own
    // wire hook. Downstream (FlagEvalLoggingHook) falls back to ErrorCode.name(), so operators
    // still get a stable signal like "TYPE_MISMATCH".
    return EvaluationResult.<T>builder()
        .value(defaultValue)
        .reason(Reason.ERROR.name())
        .errorCode(code)
        .errorMessage(observeFullEvaluationData ? errorMessage : null)
        .flagMetadata(consentMetadata(observeFullEvaluationData))
        .build();
  }

  private static boolean isEmpty(final List<?> list) {
    return list == null || list.isEmpty();
  }

  public static boolean isAllocationActive(final Allocation allocation, final Instant now) {
    final Instant startDate = allocation.startAtInstant();
    if (startDate != null && now.isBefore(startDate)) {
      return false;
    }

    final Instant endDate = allocation.endAtInstant();
    if (endDate != null && now.isAfter(endDate)) {
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
      case SEMVER_EQ:
        return evaluateSemverCondition(attributeValue, condition.semverComparand, (o) -> o == 0);
      case SEMVER_NEQ:
        return evaluateSemverCondition(attributeValue, condition.semverComparand, (o) -> o != 0);
      case SEMVER_LT:
        return evaluateSemverCondition(attributeValue, condition.semverComparand, (o) -> o < 0);
      case SEMVER_LTE:
        return evaluateSemverCondition(attributeValue, condition.semverComparand, (o) -> o <= 0);
      case SEMVER_GT:
        return evaluateSemverCondition(attributeValue, condition.semverComparand, (o) -> o > 0);
      case SEMVER_GTE:
        return evaluateSemverCondition(attributeValue, condition.semverComparand, (o) -> o >= 0);
      default:
        return false;
    }
  }

  private static boolean matchesRegex(final Object attributeValue, final Object conditionValue) {
    // PatternSyntaxException is intentionally not caught here so it propagates to evaluate(),
    // which maps it to ErrorCode.PARSE_ERROR.
    final Pattern pattern = Pattern.compile(normalizeRegex(String.valueOf(conditionValue)));
    return pattern.matcher(String.valueOf(attributeValue)).find();
  }

  private static String normalizeRegex(final String regex) {
    return regex
        .replace("[:alnum:]", "\\p{Alnum}")
        .replace("[:alpha:]", "\\p{Alpha}")
        .replace("[:digit:]", "\\p{Digit}")
        .replace("[:lower:]", "\\p{Lower}")
        .replace("[:upper:]", "\\p{Upper}")
        .replace("[:space:]", "\\p{Space}");
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

  /**
   * Evaluates a semantic version comparison operator. The attribute value must be a string that is
   * a valid semantic version, and the comparand must have been pre-parsed during configuration
   * validation. If either is missing or invalid, the condition does not match.
   */
  private static boolean evaluateSemverCondition(
      final Object attributeValue,
      final ParsedSemver comparand,
      final SemverComparator comparator) {
    if (!(attributeValue instanceof String) || comparand == null) {
      return false;
    }
    final ParsedSemver parsedAttribute = ParsedSemver.parse((String) attributeValue);
    if (parsedAttribute == null) {
      return false;
    }
    return comparator.compare(ParsedSemver.compare(parsedAttribute, comparand));
  }

  private static boolean matchesShard(final Shard shard, final String targetingKey) {
    // The bootstrap model preserves its int ABI, so UFC uint32 values are stored as raw bits.
    // Convert before arithmetic and comparison to retain their unsigned wire semantics.
    final long totalShards = Integer.toUnsignedLong(shard.totalShards);
    final long assignedShard = getShard(shard.salt, targetingKey, totalShards);
    for (final ShardRange range : shard.ranges) {
      final long rangeStart = Integer.toUnsignedLong(range.start);
      final long rangeEnd = Integer.toUnsignedLong(range.end);
      if (assignedShard >= rangeStart && assignedShard < rangeEnd) {
        return true;
      }
    }
    return false;
  }

  private static long getShard(
      final String salt, final String targetingKey, final long totalShards) {
    final String hashKey = salt + "-" + targetingKey;
    final String md5Hash = getMD5Hash(hashKey);
    final String first8Chars = md5Hash.substring(0, Math.min(8, md5Hash.length()));
    final long intFromHash = Long.parseLong(first8Chars, 16);
    return intFromHash % totalShards;
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

  private <T> EvaluationResult<T> resolveVariant(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final Flag flag,
      final String variationKey,
      final Allocation allocation,
      final Split split,
      final EvaluationContext context,
      final long evalTimestampMs,
      final boolean observeFullEvaluationData) {
    final Variant variant = flag.variations.get(variationKey);
    if (variant == null) {
      return error(
          defaultValue,
          ErrorCode.GENERAL,
          "Variant not found for: " + variationKey,
          observeFullEvaluationData);
    }

    if (!isTypeCompatible(target, flag.variationType)) {
      return error(
          defaultValue,
          ErrorCode.TYPE_MISMATCH,
          "Requested type "
              + target.getSimpleName()
              + " does not match flag variationType "
              + flag.variationType.name(),
          observeFullEvaluationData);
    }

    final T mappedValue;
    try {
      mappedValue = mapValue(target, variant.value);
    } catch (final NumberFormatException e) {
      return error(
          defaultValue,
          ErrorCode.PARSE_ERROR,
          "Variant '"
              + variant.key
              + "' value does not match declared type "
              + flag.variationType.name()
              + ": "
              + e.getMessage(),
          observeFullEvaluationData);
    }

    // Stamp eval-time at the resolution point so first/last_evaluation reflect evaluation time,
    // not hook-fire time. Passed to the hook via provider metadata "__dd_eval_timestamp_ms".
    final EvaluationMetadata.EvaluationMetadataBuilder metadataBuilder =
        EvaluationMetadata.builder()
            .addString("flagKey", flag.key)
            .addString("variationType", flag.variationType.name())
            .addString("allocationKey", allocation.key)
            .addLong("__dd_eval_timestamp_ms", evalTimestampMs)
            .addBoolean(METADATA_OBSERVE_FULL_EVALUATION_DATA, observeFullEvaluationData);
    // Surface the UFC split's serial id and the allocation's doLog flag for APM span enrichment —
    // only when span enrichment is on, so a provider without enrichment pays nothing extra.
    // __dd_split_serial_id is omitted when the split carries no serial id; __dd_do_log is always
    // present (when enrichment is on) so the span-enrichment hook can decide whether to record the
    // subject.
    if (spanEnrichmentEnabled) {
      if (split.serialId != null) {
        metadataBuilder.addInteger(METADATA_SPLIT_SERIAL_ID, split.serialId);
      }
      metadataBuilder.addBoolean(METADATA_DO_LOG, allocation.doLog != null && allocation.doLog);
    }
    final EvaluationResult<T> result =
        EvaluationResult.<T>builder()
            .value(mappedValue)
            .reason(
                !isEmpty(allocation.rules)
                    ? Reason.TARGETING_MATCH.name()
                    : allocation.startAt != null || allocation.endAt != null
                        ? Reason.DEFAULT.name()
                        : !isEmpty(split.shards) ? Reason.SPLIT.name() : Reason.STATIC.name())
            .variant(variant.key)
            .flagMetadata(metadataBuilder.build())
            .logExposure(Boolean.TRUE.equals(allocation.doLog))
            .build();
    return result;
  }

  private static Object resolveAttribute(final String name, final EvaluationContext context) {
    // Special handling for "id" attribute: if not explicitly provided, use targeting key
    if ("id".equals(name) && !context.hasAttribute(name)) {
      return context.getTargetingKey();
    }
    return context.attribute(name);
  }

  public static boolean isTypeCompatible(final Class<?> target, final ValueType variationType) {
    if (variationType == null) {
      return true; // No type info — allow any
    }
    switch (variationType) {
      case BOOLEAN:
        return target == Boolean.class;
      case STRING:
        return target == String.class;
      case INTEGER:
        return target == Integer.class;
      case NUMERIC:
        return target == Double.class;
      case JSON:
        return target == Object.class;
      default:
        return true; // Unknown types pass through — mapValue errors caught as GENERAL
    }
  }

  @SuppressWarnings("unchecked")
  public static <T> T mapValue(final Class<T> target, final Object value) {
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
    return (T) value;
  }

  private static Double parseDouble(final Object value) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  @FunctionalInterface
  private interface NumberComparator {
    boolean compare(double a, double b);
  }

  @FunctionalInterface
  private interface SemverComparator {
    boolean compare(int ordering);
  }
}
