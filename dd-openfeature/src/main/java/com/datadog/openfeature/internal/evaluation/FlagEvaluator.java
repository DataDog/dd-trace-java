package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVAL_TIMESTAMP_MS;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.HAS_EVALUATION_FEATURES;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.OBSERVE_FULL_EVALUATION_DATA;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;
import static java.util.Arrays.asList;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.ConditionOperator;
import com.datadog.openfeature.internal.ufc.Feature;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ParsedSemver;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Shard;
import com.datadog.openfeature.internal.ufc.ShardRange;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local evaluation over a single configuration snapshot. No transport dependency. */
public final class FlagEvaluator {
  private static final Logger LOGGER = LoggerFactory.getLogger(FlagEvaluator.class);
  private static final Set<Class<?>> SUPPORTED_RESOLUTION_TYPES =
      new HashSet<>(asList(String.class, Boolean.class, Integer.class, Double.class, Value.class));
  static final String FEATURE_DESTINATION_HOOK = "HOOK";
  static final String FEATURE_DESTINATION_EXPOSURE = "EXPOSURE";
  static final String FEATURE_DESTINATION_EVALUATION = "EVALUATION";
  static final int DEFAULT_MAX_DEPENDENCY_DEPTH = 10;
  // A newer UFC can name destinations this SDK does not know; warn once per name, not per
  // evaluation.
  static final int MAX_WARNED_FEATURE_DESTINATIONS = 64;
  static final Set<String> WARNED_FEATURE_DESTINATIONS = ConcurrentHashMap.newKeySet();
  private final boolean spanEnrichmentEnabled;
  private final ExposureLookup exposureLookup;
  private final DependencyEvaluationSink dependencyEvaluationSink;
  private final DependencyExposureSink dependencyExposureSink;

  /**
   * Creates an evaluator.
   *
   * @param spanEnrichmentEnabled whether span enrichment is enabled.
   * @param exposureLookup the lookup telling whether an exposure was already recorded.
   */
  public FlagEvaluator(final boolean spanEnrichmentEnabled, final ExposureLookup exposureLookup) {
    this(
        spanEnrichmentEnabled,
        exposureLookup,
        (flag, details, context, timestamp) -> {},
        (items, context) -> {});
  }

  public FlagEvaluator(
      final boolean spanEnrichmentEnabled,
      final ExposureLookup exposureLookup,
      final DependencyEvaluationSink dependencyEvaluationSink,
      final DependencyExposureSink dependencyExposureSink) {
    this.spanEnrichmentEnabled = spanEnrichmentEnabled;
    this.exposureLookup = exposureLookup;
    this.dependencyEvaluationSink = dependencyEvaluationSink;
    this.dependencyExposureSink = dependencyExposureSink;
  }

  public <T> ProviderEvaluation<T> evaluate(
      final ServerConfiguration config,
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    // Snapshot the config once and thread observeFullEvaluationData through every
    // ProviderEvaluation returned, so the hook's consent decision is pinned to this evaluation's
    // config and cannot drift on a concurrent Remote Config swap.
    // Boolean.TRUE.equals covers both null (privacy-preserving default) and Boolean.FALSE without
    // an NPE — the field is boxed so a malformed UFC message doesn't abort the whole parse.
    final boolean observeFullEvaluationData =
        config != null && Boolean.TRUE.equals(config.observeFullEvaluationData);
    final EvaluationState state =
        new EvaluationState(config, context, observeFullEvaluationData, System.currentTimeMillis());
    try {
      final ProviderEvaluation<T> result = evaluateFlag(state, target, key, defaultValue);
      state.complete(result.getErrorCode() == null);
      return result;
    } catch (final DependencyGraphException e) {
      state.complete(false);
      return error(defaultValue, e.errorCode, e.getMessage(), observeFullEvaluationData);
    } catch (final PatternSyntaxException e) {
      state.complete(false);
      return error(defaultValue, ErrorCode.PARSE_ERROR, e.getMessage(), observeFullEvaluationData);
    } catch (final NumberFormatException e) {
      state.complete(false);
      return error(
          defaultValue, ErrorCode.TYPE_MISMATCH, e.getMessage(), observeFullEvaluationData);
    } catch (final Exception e) {
      state.complete(false);
      return error(defaultValue, ErrorCode.GENERAL, e.getMessage(), observeFullEvaluationData);
    }
  }

  private <T> ProviderEvaluation<T> evaluateFlag(
      final EvaluationState state, final Class<T> target, final String key, final T defaultValue) {
    final ServerConfiguration config = state.config;
    if (config == null) {
      return error(
          defaultValue, ErrorCode.PROVIDER_NOT_READY, null, state.observeFullEvaluationData);
    }
    if (state.context == null) {
      return error(defaultValue, ErrorCode.INVALID_CONTEXT, null, state.observeFullEvaluationData);
    }
    final Flag flag = config.flags == null ? null : config.flags.get(key);
    if (flag == null) {
      if (config.invalidFlags != null && config.invalidFlags.containsKey(key)) {
        return error(
            defaultValue,
            ErrorCode.PARSE_ERROR,
            "invalid configuration for flag " + key,
            state.observeFullEvaluationData);
      }
      return error(defaultValue, ErrorCode.FLAG_NOT_FOUND, null, state.observeFullEvaluationData);
    }
    if (!flag.enabled) {
      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(Reason.DISABLED.name())
          .flagMetadata(consentMetadata(state.observeFullEvaluationData))
          .build();
    }
    if (flag.allocations == null) {
      return error(
          defaultValue,
          ErrorCode.GENERAL,
          "Missing allocations for flag " + key,
          state.observeFullEvaluationData);
    }

    final Instant now = Instant.ofEpochMilli(state.evaluationTimestampMs);
    final String targetingKey = state.context.getTargetingKey();
    for (final Allocation allocation : flag.allocations) {
      if (!isAllocationActive(allocation, now)) {
        continue;
      }
      final ExposureCheckpoint allocationCheckpoint = state.checkpoint();
      if (!isEmpty(allocation.rules)
          && !evaluateRules(allocation.rules, state, key, state.context)) {
        state.rollback(allocationCheckpoint);
        continue;
      }
      if (!isEmpty(allocation.splits)) {
        for (final Split split : allocation.splits) {
          boolean allShardsMatch = true;
          if (!isEmpty(split.shards)) {
            if (targetingKey == null) {
              state.rollback(allocationCheckpoint);
              return error(
                  defaultValue,
                  ErrorCode.TARGETING_KEY_MISSING,
                  null,
                  state.observeFullEvaluationData);
            }
            for (final Shard shard : split.shards) {
              if (!matchesShard(shard, targetingKey)) {
                allShardsMatch = false;
                break;
              }
            }
          }
          if (allShardsMatch) {
            return resolveVariant(
                target,
                defaultValue,
                flag,
                split.variationKey,
                allocation,
                split,
                state.context,
                state.evaluationTimestampMs,
                state.observeFullEvaluationData);
          }
        }
      }
      state.rollback(allocationCheckpoint);
    }
    return ProviderEvaluation.<T>builder()
        .value(defaultValue)
        .reason(Reason.DEFAULT.name())
        .flagMetadata(consentMetadata(state.observeFullEvaluationData))
        .build();
  }

  private static int maxDependencyDepth(final ServerConfiguration config) {
    if (config.evaluatorParams == null || !config.evaluatorParams.maxDependencyDepthPresent) {
      return DEFAULT_MAX_DEPENDENCY_DEPTH;
    }
    final Object configured = config.evaluatorParams.maxDependencyDepth;
    if (configured instanceof Number) {
      final double value = ((Number) configured).doubleValue();
      if (Double.isFinite(value) && value == Math.rint(value) && value >= 0 && value <= 255) {
        return (int) value;
      }
    }
    throw new DependencyGraphException(
        ErrorCode.PARSE_ERROR, "maxDependencyDepth must be an integer from 0 through 255");
  }

  private static ImmutableMetadata consentMetadata(final boolean observeFullEvaluationData) {
    return ImmutableMetadata.builder()
        .addBoolean(OBSERVE_FULL_EVALUATION_DATA, observeFullEvaluationData)
        .build();
  }

  private static <T> ProviderEvaluation<T> error(
      final T defaultValue,
      final ErrorCode code,
      final String errorMessage,
      final boolean observeFullEvaluationData) {
    // Under consent-off the errorMessage is dropped: exception messages from the outer catch blocks
    // (NumberFormatException, generic Exception) can echo raw evaluation-context values, so they
    // must never reach any consumer of ProviderEvaluation.getErrorMessage() — not just our own
    // wire hook. Downstream (FlagEvalLoggingHook) falls back to ErrorCode.name(), so operators
    // still get a stable signal like "TYPE_MISMATCH".
    return ProviderEvaluation.<T>builder()
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
    final Instant startDate = allocation.startAt;
    if (startDate != null && now.isBefore(startDate)) {
      return false;
    }

    final Instant endDate = allocation.endAt;
    if (endDate != null && now.isAfter(endDate)) {
      return false;
    }

    return true;
  }

  private boolean evaluateRules(
      final List<Rule> rules,
      final EvaluationState state,
      final String rootFlagKey,
      final EvaluationContext context) {
    for (final Rule rule : rules) {
      if (isEmpty(rule.conditions)) {
        continue;
      }

      final ExposureCheckpoint ruleCheckpoint = state.checkpoint();
      boolean allConditionsMatch = true;
      for (final ConditionConfiguration condition : rule.conditions) {
        if (!evaluateCondition(condition, state, rootFlagKey, context)) {
          allConditionsMatch = false;
          break;
        }
      }

      if (allConditionsMatch) {
        return true;
      }
      state.rollback(ruleCheckpoint);
    }
    return false;
  }

  private boolean evaluateCondition(
      final ConditionConfiguration condition,
      final EvaluationState state,
      final String rootFlagKey,
      final EvaluationContext context) {
    final Object attributeValue;
    if (condition.flagEvaluationKey != null) {
      attributeValue =
          state.dependencySession(rootFlagKey).resolveCurrent(condition.flagEvaluationKey);
    } else {
      attributeValue = resolveAttribute(condition.attribute, context);
    }
    if (condition.operator == ConditionOperator.IS_NULL) {
      boolean isNull = attributeValue == null;
      // condition.value determines if we're checking for null (true) or not null (false)
      boolean expectedNull = condition.value instanceof Boolean ? (Boolean) condition.value : true;
      return isNull == expectedNull;
    }

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
    final long assignedShard = getShard(shard.salt, targetingKey, shard.totalShards);
    for (final ShardRange range : shard.ranges) {
      if (assignedShard >= range.start && assignedShard < range.end) {
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

  private <T> ProviderEvaluation<T> resolveVariant(
      final Class<T> target,
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
    final ImmutableMetadata.ImmutableMetadataBuilder metadataBuilder =
        ImmutableMetadata.builder()
            .addString("flagKey", flag.key)
            .addString("variationType", flag.variationType.name())
            .addString(ALLOCATION_KEY, allocation.key)
            .addLong(EVAL_TIMESTAMP_MS, evalTimestampMs)
            .addBoolean(OBSERVE_FULL_EVALUATION_DATA, observeFullEvaluationData);
    // Surface the UFC split's serial id and the allocation's doLog flag for APM span enrichment and
    // for the exposure hook, which sends the exposure after the evaluation succeeds. An evaluation
    // that neither logs exposures nor enriches spans carries neither key.
    // __dd_split_serial_id is omitted when the split carries no serial id.
    final boolean doLog = Boolean.TRUE.equals(allocation.doLog);
    if (spanEnrichmentEnabled || doLog) {
      if (split.serialId != null) {
        metadataBuilder.addInteger(SPLIT_SERIAL_ID, split.serialId);
      }
      metadataBuilder.addBoolean(DO_LOG, doLog);
    }
    addSplitFeatures(metadataBuilder, split);
    if (doLog) {
      metadataBuilder.addBoolean(
          EXPOSURE_CACHE_HIT,
          this.exposureLookup.isExposed(
              flag.key, context.getTargetingKey(), allocation.key, variant.key, split.serialId));
    }
    return ProviderEvaluation.<T>builder()
        .value(mappedValue)
        .reason(
            !isEmpty(allocation.rules)
                ? Reason.TARGETING_MATCH.name()
                : allocation.startAt != null || allocation.endAt != null
                    ? Reason.DEFAULT.name()
                    : !isEmpty(split.shards) ? Reason.SPLIT.name() : Reason.STATIC.name())
        .variant(variant.key)
        .flagMetadata(metadataBuilder.build())
        .build();
  }

  private static void addSplitFeatures(
      final ImmutableMetadata.ImmutableMetadataBuilder metadataBuilder, final Split split) {
    if (split.features == null) {
      return;
    }
    Set<String> seenKeys = null;
    boolean evaluationFeatures = false;
    for (final Feature feature : split.features) {
      if (feature == null || feature.key == null || feature.destinations == null) {
        continue;
      }
      if (seenKeys == null) {
        seenKeys = new HashSet<>();
      }
      // The first feature with a key wins, for every destination.
      if (!seenKeys.add(feature.key)) {
        continue;
      }
      for (final String destination : feature.destinations) {
        final String prefix = metadataPrefix(destination);
        if (prefix != null) {
          addFeature(metadataBuilder, prefix + feature.key, feature.value);
          evaluationFeatures |= EVALUATION_FEATURE_PREFIX.equals(prefix);
        } else if (WARNED_FEATURE_DESTINATIONS.size() < MAX_WARNED_FEATURE_DESTINATIONS
            && WARNED_FEATURE_DESTINATIONS.add(destination)) {
          LOGGER.warn("Ignoring unknown feature destination {}", destination);
        }
      }
    }
    if (evaluationFeatures) {
      metadataBuilder.addBoolean(HAS_EVALUATION_FEATURES, true);
    }
  }

  private static String metadataPrefix(final String destination) {
    if (FEATURE_DESTINATION_HOOK.equals(destination)) {
      return FEATURE_PREFIX;
    }
    if (FEATURE_DESTINATION_EXPOSURE.equals(destination)) {
      return EXPOSURE_FEATURE_PREFIX;
    }
    if (FEATURE_DESTINATION_EVALUATION.equals(destination)) {
      return EVALUATION_FEATURE_PREFIX;
    }
    return null;
  }

  private static void addFeature(
      final ImmutableMetadata.ImmutableMetadataBuilder metadataBuilder,
      final String key,
      final Object value) {
    if (value instanceof String) {
      metadataBuilder.addString(key, (String) value);
    } else if (value instanceof Boolean) {
      metadataBuilder.addBoolean(key, (Boolean) value);
    } else if (value instanceof Number) {
      metadataBuilder.addDouble(key, ((Number) value).doubleValue());
    }
  }

  private static Object resolveAttribute(final String name, final EvaluationContext context) {
    // Special handling for "id" attribute: if not explicitly provided, use targeting key
    if ("id".equals(name) && !context.keySet().contains(name)) {
      return context.getTargetingKey();
    }
    return context.convertValue(context.getValue(name));
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
        return target == Value.class;
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
    return (T) Value.objectToValue(value);
  }

  private static Double parseDouble(final Object value) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  private final class EvaluationState {
    private final ServerConfiguration config;
    private final EvaluationContext context;
    private final boolean observeFullEvaluationData;
    private final long evaluationTimestampMs;
    private DependencyEvaluationSession session;

    private EvaluationState(
        final ServerConfiguration config,
        final EvaluationContext context,
        final boolean observeFullEvaluationData,
        final long evaluationTimestampMs) {
      this.config = config;
      this.context = context;
      this.observeFullEvaluationData = observeFullEvaluationData;
      this.evaluationTimestampMs = evaluationTimestampMs;
    }

    private DependencyEvaluationSession dependencySession(final String rootFlagKey) {
      if (session == null) {
        session = new DependencyEvaluationSession(rootFlagKey, maxDependencyDepth(config), this);
      }
      return session;
    }

    private ExposureCheckpoint checkpoint() {
      return session == null ? ExposureCheckpoint.EMPTY : session.checkpoint();
    }

    private void rollback(final ExposureCheckpoint checkpoint) {
      if (session != null) {
        session.rollback(checkpoint);
      }
    }

    private void complete(final boolean commitExposures) {
      if (session != null) {
        session.complete(commitExposures);
      }
    }
  }

  private final class DependencyEvaluationSession {
    private final int maxDependencyDepth;
    private final EvaluationState state;
    private final Map<String, MemoizedDependency> memo = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private final List<DependencyFrame> frames = new ArrayList<>();
    private final List<DependencyExposure> exposures = new ArrayList<>();
    private boolean completed;

    private DependencyEvaluationSession(
        final String rootFlagKey, final int maxDependencyDepth, final EvaluationState state) {
      this.maxDependencyDepth = maxDependencyDepth;
      this.state = state;
      active.add(rootFlagKey);
    }

    private ExposureCheckpoint checkpoint() {
      return new ExposureCheckpoint(
          exposures.size(),
          frames.isEmpty() ? 0 : frames.get(frames.size() - 1).exposureTrace.size());
    }

    private void rollback(final ExposureCheckpoint checkpoint) {
      while (exposures.size() > checkpoint.exposureCount) {
        exposures.remove(exposures.size() - 1);
      }
      if (!frames.isEmpty()) {
        final DependencyFrame frame = frames.get(frames.size() - 1);
        while (frame.exposureTrace.size() > checkpoint.frameExposureCount) {
          frame.exposureTrace.remove(frame.exposureTrace.size() - 1);
        }
      }
    }

    private void complete(final boolean commitExposures) {
      if (completed) {
        return;
      }
      completed = true;
      if (commitExposures && !exposures.isEmpty()) {
        try {
          dependencyExposureSink.record(new ArrayList<>(exposures), state.context);
        } catch (final RuntimeException | LinkageError ignored) {
          // Telemetry is best effort and must not change evaluation semantics.
        }
      }
    }

    private String resolveCurrent(final String flagKey) {
      final int depth = frames.isEmpty() ? 1 : frames.get(frames.size() - 1).depth + 1;
      return resolve(flagKey, depth);
    }

    private String resolve(final String flagKey, final int depth) {
      if (depth > maxDependencyDepth) {
        if (!memo.containsKey(flagKey) && !active.contains(flagKey)) {
          throw emitFailure(flagKey, ErrorCode.GENERAL, "maximum flag dependency depth exceeded");
        }
        throw new DependencyGraphException(
            ErrorCode.GENERAL, "maximum flag dependency depth exceeded");
      }
      if (active.contains(flagKey)) {
        throw new DependencyGraphException(ErrorCode.GENERAL, "flag dependency cycle detected");
      }

      final MemoizedDependency memoized = memo.get(flagKey);
      if (memoized != null) {
        if (depth + memoized.descendantDepth > maxDependencyDepth) {
          throw new DependencyGraphException(
              ErrorCode.GENERAL, "maximum flag dependency depth exceeded");
        }
        recordReachedDepth(depth + memoized.descendantDepth);
        recordExposureTrace(memoized.exposureTrace);
        return memoized.details.getVariant();
      }

      final Flag flag = state.config.flags == null ? null : state.config.flags.get(flagKey);
      if (flag == null) {
        final ProviderEvaluation<Object> missing =
            error(
                null,
                state.config.invalidFlags != null && state.config.invalidFlags.containsKey(flagKey)
                    ? ErrorCode.PARSE_ERROR
                    : ErrorCode.FLAG_NOT_FOUND,
                "prerequisite flag is missing",
                state.observeFullEvaluationData);
        emitEvaluation(flagKey, missing);
        throw new DependencyGraphException(
            missing.getErrorCode(),
            missing.getErrorCode() == ErrorCode.FLAG_NOT_FOUND
                ? "prerequisite flag is missing"
                : "invalid prerequisite flag configuration");
      }

      active.add(flagKey);
      final DependencyFrame frame = new DependencyFrame(depth);
      frames.add(frame);
      try {
        final ProviderEvaluation<?> details = evaluateDeclaredFlag(state, flagKey, flag);
        if (details.getErrorCode() != null || Reason.ERROR.name().equals(details.getReason())) {
          throw new DependencyGraphException(
              details.getErrorCode() == null ? ErrorCode.GENERAL : details.getErrorCode(),
              details.getErrorMessage() == null
                  ? "prerequisite flag evaluation failed"
                  : details.getErrorMessage());
        }
        if (details.getVariant() == null) {
          throw new DependencyGraphException(
              ErrorCode.GENERAL, "prerequisite flag evaluation returned no variant");
        }
        final List<DependencyExposure> exposureTrace = new ArrayList<>(frame.exposureTrace);
        appendUnique(exposureTrace, new DependencyExposure(flagKey, details));
        final MemoizedDependency result =
            new MemoizedDependency(details, frame.maxReachedDepth - depth, exposureTrace);
        memo.put(flagKey, result);
        emitEvaluation(flagKey, details);
        recordReachedDepthOnParent(frame.maxReachedDepth);
        recordExposureTraceOnParent(exposureTrace);
        return details.getVariant();
      } catch (final DependencyGraphException e) {
        final ProviderEvaluation<Object> failure =
            error(null, e.errorCode, e.getMessage(), state.observeFullEvaluationData);
        emitEvaluation(flagKey, failure);
        throw e;
      } catch (final PatternSyntaxException e) {
        throw emitFailure(flagKey, ErrorCode.PARSE_ERROR, e.getMessage());
      } catch (final NumberFormatException e) {
        throw emitFailure(flagKey, ErrorCode.TYPE_MISMATCH, e.getMessage());
      } catch (final Exception e) {
        throw emitFailure(flagKey, ErrorCode.GENERAL, e.getMessage());
      } finally {
        frames.remove(frames.size() - 1);
        active.remove(flagKey);
      }
    }

    private DependencyGraphException emitFailure(
        final String flagKey, final ErrorCode errorCode, final String message) {
      final DependencyGraphException failure =
          new DependencyGraphException(errorCode, message == null ? errorCode.name() : message);
      emitEvaluation(
          flagKey, error(null, errorCode, failure.getMessage(), state.observeFullEvaluationData));
      return failure;
    }

    private void emitEvaluation(final String flagKey, final ProviderEvaluation<?> details) {
      try {
        dependencyEvaluationSink.record(
            flagKey, details, state.context, state.evaluationTimestampMs);
      } catch (final RuntimeException | LinkageError ignored) {
        // Telemetry is best effort and must not change evaluation semantics.
      }
    }

    private void recordReachedDepth(final int depth) {
      if (!frames.isEmpty()) {
        final DependencyFrame frame = frames.get(frames.size() - 1);
        frame.maxReachedDepth = Math.max(frame.maxReachedDepth, depth);
      }
    }

    private void recordReachedDepthOnParent(final int depth) {
      if (frames.size() > 1) {
        final DependencyFrame parent = frames.get(frames.size() - 2);
        parent.maxReachedDepth = Math.max(parent.maxReachedDepth, depth);
      }
    }

    private void recordExposureTrace(final List<DependencyExposure> trace) {
      for (final DependencyExposure candidate : trace) {
        appendUnique(exposures, candidate);
        if (!frames.isEmpty()) {
          appendUnique(frames.get(frames.size() - 1).exposureTrace, candidate);
        }
      }
    }

    private void recordExposureTraceOnParent(final List<DependencyExposure> trace) {
      for (final DependencyExposure candidate : trace) {
        appendUnique(exposures, candidate);
        if (frames.size() > 1) {
          appendUnique(frames.get(frames.size() - 2).exposureTrace, candidate);
        }
      }
    }
  }

  private ProviderEvaluation<?> evaluateDeclaredFlag(
      final EvaluationState state, final String flagKey, final Flag flag) {
    if (flag.variationType == null) {
      throw new DependencyGraphException(
          ErrorCode.PARSE_ERROR, "prerequisite flag has no variation type");
    }
    switch (flag.variationType) {
      case BOOLEAN:
        return evaluateFlag(state, Boolean.class, flagKey, Boolean.FALSE);
      case INTEGER:
        return evaluateFlag(state, Integer.class, flagKey, 0);
      case NUMERIC:
        return evaluateFlag(state, Double.class, flagKey, 0D);
      case STRING:
        return evaluateFlag(state, String.class, flagKey, "");
      case JSON:
        return evaluateFlag(state, Value.class, flagKey, new Value());
      default:
        throw new DependencyGraphException(
            ErrorCode.PARSE_ERROR, "unsupported prerequisite variation type");
    }
  }

  private static void appendUnique(
      final List<DependencyExposure> target, final DependencyExposure candidate) {
    for (final DependencyExposure existing : target) {
      if (existing.flagKey.equals(candidate.flagKey)) {
        return;
      }
    }
    target.add(candidate);
  }

  private static final class DependencyFrame {
    private final int depth;
    private int maxReachedDepth;
    private final List<DependencyExposure> exposureTrace = new ArrayList<>();

    private DependencyFrame(final int depth) {
      this.depth = depth;
      this.maxReachedDepth = depth;
    }
  }

  private static final class ExposureCheckpoint {
    private static final ExposureCheckpoint EMPTY = new ExposureCheckpoint(0, 0);
    private final int exposureCount;
    private final int frameExposureCount;

    private ExposureCheckpoint(final int exposureCount, final int frameExposureCount) {
      this.exposureCount = exposureCount;
      this.frameExposureCount = frameExposureCount;
    }
  }

  private static final class MemoizedDependency {
    private final ProviderEvaluation<?> details;
    private final int descendantDepth;
    private final List<DependencyExposure> exposureTrace;

    private MemoizedDependency(
        final ProviderEvaluation<?> details,
        final int descendantDepth,
        final List<DependencyExposure> exposureTrace) {
      this.details = details;
      this.descendantDepth = descendantDepth;
      this.exposureTrace = exposureTrace;
    }
  }

  private static final class DependencyGraphException extends RuntimeException {
    private final ErrorCode errorCode;

    private DependencyGraphException(final ErrorCode errorCode, final String message) {
      super(message);
      this.errorCode = errorCode;
    }
  }

  public static final class DependencyExposure {
    public final String flagKey;
    public final ProviderEvaluation<?> details;

    private DependencyExposure(final String flagKey, final ProviderEvaluation<?> details) {
      this.flagKey = flagKey;
      this.details = details;
    }
  }

  @FunctionalInterface
  public interface DependencyEvaluationSink {
    void record(
        String flagKey,
        ProviderEvaluation<?> details,
        EvaluationContext context,
        long evaluationTimestampMs);
  }

  @FunctionalInterface
  public interface DependencyExposureSink {
    void record(List<DependencyExposure> exposures, EvaluationContext context);
  }

  /** Tells whether an exposure was already recorded, so it is not sent again. */
  @FunctionalInterface
  public interface ExposureLookup {
    /**
     * Tells whether this exposure is the last one recorded for the flag and subject.
     *
     * @param flag the flag key.
     * @param subject the subject, the targeting key.
     * @param allocation the allocation key.
     * @param variant the variant key.
     * @param serialId the split serial id, or {@code null} if absent.
     * @return whether the exposure was already recorded.
     */
    boolean isExposed(
        String flag, String subject, String allocation, String variant, Integer serialId);
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
