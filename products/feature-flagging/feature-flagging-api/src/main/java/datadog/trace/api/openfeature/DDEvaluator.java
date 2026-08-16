package datadog.trace.api.openfeature;

import static java.util.Arrays.asList;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEventMemoryEstimator;
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
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

class DDEvaluator implements Evaluator, FeatureFlaggingGateway.ConfigListener {

  private static final Set<Class<?>> SUPPORTED_RESOLUTION_TYPES =
      new HashSet<>(asList(String.class, Boolean.class, Integer.class, Double.class, Value.class));

  /**
   * Maximum evaluation-context nesting depth captured on the hot path. Recursion runs on the
   * caller's evaluation thread over a caller-owned Value tree, so an arbitrarily deep
   * list/structure would overflow that thread's stack - and a StackOverflowError is not caught by
   * the LinkageError | Exception guards that keep telemetry from breaking an evaluation. Values
   * below the limit are truncated to null, the same way the cycle guard truncates. Kept aligned
   * with the cross-SDK RFC target (4).
   */
  static final int MAX_SNAPSHOT_DEPTH = 4;

  /**
   * Maximum number of top-level context fields retained by copyPrunedContext. Bounds the width of
   * the caller-supplied context and, transitively, the size of every FlagEvalEvent sitting in the
   * async hand-off queue. Kept aligned with the cross-SDK RFC.
   */
  static final int MAX_CONTEXT_FIELDS = 256;

  /**
   * Maximum character length for a single context KEY retained by copyPrunedContext. Keys are
   * stored verbatim in every full-tier bucket, so an unbounded key size would let a single caller
   * inflate steady-state heap use. Longer keys cause the field to be skipped.
   */
  static final int MAX_KEY_LENGTH = 256;

  /**
   * Maximum character length for a single context string VALUE retained by copyPrunedContext.
   * Longer values cause the field to be skipped (matches previous pruneContext behavior).
   * Non-string scalars are not length-bounded.
   */
  static final int MAX_VALUE_LENGTH = 256;

  /**
   * Maximum number of elements walked per list encountered during copyPrunedContext. Bounds the
   * fan-out of a single wide list at capture time so one caller cannot inflate the hot path with a
   * huge but shallow structure. Elements past the limit are skipped.
   */
  static final int MAX_LIST_ELEMENTS = 256;

  /**
   * Maximum number of properties walked per structure encountered during copyPrunedContext. Same
   * intent as MAX_LIST_ELEMENTS for structures. Properties past the limit are skipped.
   */
  static final int MAX_STRUCTURE_PROPERTIES = 256;

  // Evaluation-metadata keys consumed by the span-enrichment capture hook (see
  // SpanEnrichmentHook). Emitted only when the span-enrichment gate is on.
  static final String METADATA_SPLIT_SERIAL_ID = "__dd_split_serial_id";
  static final String METADATA_DO_LOG = "__dd_do_log";

  // Stamped on every DD-produced evaluation (including PROVIDER_NOT_READY, with false). Missing
  // key = non-DD provider; the hook falls back to false (fail-closed).
  static final String METADATA_OBSERVE_FULL_EVALUATION_DATA = "observe_full_evaluation_data";

  // Read once: when off, the __dd_* span-enrichment metadata is not attached to evaluations, so an
  // enabled provider pays nothing extra unless span enrichment is also enabled. The gate does not
  // change at runtime, and this class is loaded lazily (well after startup) so config is ready.
  private static final boolean SPAN_ENRICHMENT_ENABLED = SpanEnrichmentGate.isEnabled();

  private final Runnable configCallback;
  private final AtomicReference<ServerConfiguration> configuration = new AtomicReference<>();
  private final CountDownLatch initializationLatch = new CountDownLatch(1);

  public DDEvaluator(final Runnable configCallback) {
    this.configCallback = configCallback;
  }

  @Override
  public boolean initialize(
      final long timeout, final TimeUnit unit, final EvaluationContext context) throws Exception {
    FeatureFlaggingGateway.activate();
    FeatureFlaggingGateway.addConfigListener(this);
    return initializationLatch.await(timeout, unit) || hasConfiguration();
  }

  @Override
  public boolean hasConfiguration() {
    return configuration.get() != null;
  }

  @Override
  public void shutdown() {
    FeatureFlaggingGateway.removeConfigListener(this);
  }

  @Override
  public void accept(final ServerConfiguration config) {
    configuration.set(config);
    if (config != null) {
      initializationLatch.countDown();
      configCallback.run();
    } else if (initializationLatch.getCount() == 0) {
      configCallback.run();
    }
  }

  @Override
  public <T> ProviderEvaluation<T> evaluate(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    // Snapshot the config once and thread observeFullEvaluationData through every
    // ProviderEvaluation returned, so the hook's consent decision is pinned to this evaluation's
    // config and cannot drift on a concurrent Remote Config swap.
    final ServerConfiguration config = configuration.get();
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
          if ("invalid_semver_comparand".equals(config.invalidFlags.get(key))) {
            return error(
                defaultValue,
                ErrorCode.PARSE_ERROR,
                "invalid configuration for flag " + key,
                observeFullEvaluationData);
          }
          return ProviderEvaluation.<T>builder()
              .value(defaultValue)
              .reason(Reason.DEFAULT.name())
              .flagMetadata(consentMetadata(observeFullEvaluationData))
              .build();
        }
        return error(defaultValue, ErrorCode.FLAG_NOT_FOUND, null, observeFullEvaluationData);
      }

      if (!flag.enabled) {
        return ProviderEvaluation.<T>builder()
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

      return ProviderEvaluation.<T>builder()
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

  private static ImmutableMetadata consentMetadata(final boolean observeFullEvaluationData) {
    return ImmutableMetadata.builder()
        .addBoolean(METADATA_OBSERVE_FULL_EVALUATION_DATA, observeFullEvaluationData)
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

  static boolean isAllocationActive(final Allocation allocation, final Instant now) {
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

  private static <T> ProviderEvaluation<T> resolveVariant(
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
    final ImmutableMetadata.ImmutableMetadataBuilder metadataBuilder =
        ImmutableMetadata.builder()
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
    if (SPAN_ENRICHMENT_ENABLED) {
      if (split.serialId != null) {
        metadataBuilder.addInteger(METADATA_SPLIT_SERIAL_ID, split.serialId);
      }
      metadataBuilder.addBoolean(METADATA_DO_LOG, allocation.doLog != null && allocation.doLog);
    }
    final ProviderEvaluation<T> result =
        ProviderEvaluation.<T>builder()
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
    final boolean doLog = allocation.doLog != null && allocation.doLog;
    if (doLog) {
      dispatchExposure(key, result, context);
    }
    return result;
  }

  private static Object resolveAttribute(final String name, final EvaluationContext context) {
    // Special handling for "id" attribute: if not explicitly provided, use targeting key
    if ("id".equals(name) && !context.keySet().contains(name)) {
      return context.getTargetingKey();
    }
    final Value resolved = context.getValue(name);
    return context.convertValue(resolved);
  }

  private static boolean isTypeCompatible(final Class<?> target, final ValueType variationType) {
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

  private static <T> void dispatchExposure(
      final String flag, final ProviderEvaluation<T> evaluation, final EvaluationContext context) {
    final String allocationKey = allocationKey(evaluation);
    final String variantKey = evaluation.getVariant();
    if (allocationKey == null || variantKey == null) {
      return;
    }
    final ExposureEvent event =
        new ExposureEvent(
            System.currentTimeMillis(),
            new datadog.trace.api.featureflag.exposure.Allocation(allocationKey),
            new datadog.trace.api.featureflag.exposure.Flag(flag),
            new datadog.trace.api.featureflag.exposure.Variant(variantKey),
            new Subject(context.getTargetingKey(), flattenContext(context)));

    FeatureFlaggingGateway.dispatch(event);
  }

  private static <T> String allocationKey(final ProviderEvaluation<T> resolution) {
    final ImmutableMetadata meta = resolution.getFlagMetadata();
    return meta == null ? null : meta.getString("allocationKey");
  }

  static AbstractMap<String, Object> flattenContext(final EvaluationContext context) {
    return flattenValues(snapshotValues(context));
  }

  static Map<String, Value> snapshotValues(final EvaluationContext context) {
    final HashMap<String, Value> values = new HashMap<>();
    final Set<Object> seenContainers = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final String key : context.keySet()) {
      values.put(key, snapshotValue(context.getValue(key), seenContainers, 0));
    }
    return values;
  }

  private static Value snapshotValue(
      final Value value, final Set<Object> seenContainers, final int depth) {
    if (value == null) {
      return null;
    } else if (value.isNull()) {
      return new Value();
    } else if (value.isBoolean()) {
      return new Value(value.asBoolean());
    } else if (value.isNumber()) {
      final Object number = value.asObject();
      return number instanceof Integer
          ? new Value((Integer) number)
          : new Value(((Number) number).doubleValue());
    } else if (value.isString()) {
      return new Value(value.asString());
    } else if (value.isInstant()) {
      return new Value(value.asInstant());
    } else if (value.isList()) {
      final List<Value> list = value.asList();
      if (depth >= MAX_SNAPSHOT_DEPTH || !seenContainers.add(list)) {
        return new Value();
      }
      final List<Value> snapshot = new ArrayList<>(list.size());
      for (final Value item : list) {
        snapshot.add(snapshotValue(item, seenContainers, depth + 1));
      }
      seenContainers.remove(list);
      return new Value(Collections.unmodifiableList(snapshot));
    } else if (value.isStructure()) {
      final Structure structure = value.asStructure();
      if (depth >= MAX_SNAPSHOT_DEPTH || !seenContainers.add(structure)) {
        return new Value();
      }
      final Map<String, Value> snapshot = new HashMap<>();
      for (final String key : structure.keySet()) {
        snapshot.put(key, snapshotValue(structure.getValue(key), seenContainers, depth + 1));
      }
      seenContainers.remove(structure);
      return new Value(new ImmutableStructure(snapshot));
    }
    throw new IllegalArgumentException("Unsupported OpenFeature value type: " + value);
  }

  static AbstractMap<String, Object> flattenValues(final Map<String, Value> values) {
    final HashMap<String, Object> result = new HashMap<>();
    final Set<Object> seenContainers = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final Map.Entry<String, Value> root : values.entrySet()) {
      final Deque<FlattenEntry> deque = new LinkedList<>();
      deque.push(new FlattenEntry(root.getKey(), root.getValue()));
      while (!deque.isEmpty()) {
        final FlattenEntry entry = deque.pop();
        final Value value = entry.value;
        if (value == null) {
          result.put(entry.key, null);
        } else if (value.isList()) {
          final List<Value> list = value.asList();
          if (seenContainers.add(list)) {
            for (int i = 0; i < list.size(); i++) {
              deque.push(new FlattenEntry(entry.key + "[" + i + "]", list.get(i)));
            }
          }
        } else if (value.isStructure()) {
          final Structure structure = value.asStructure();
          if (seenContainers.add(structure)) {
            for (final String property : structure.keySet()) {
              deque.push(
                  new FlattenEntry(entry.key + "." + property, structure.getValue(property)));
            }
          }
        } else {
          result.put(entry.key, convertValue(value));
        }
      }
    }
    return result;
  }

  private static Object convertValue(final Value value) {
    if (value == null || value.isNull()) {
      return null;
    } else if (value.isBoolean()) {
      return value.asBoolean();
    } else if (value.isNumber()) {
      return value.asObject();
    } else if (value.isString()) {
      return value.asString();
    } else if (value.isInstant()) {
      return value.asInstant().toString();
    }
    throw new IllegalArgumentException("Unsupported OpenFeature value type: " + value);
  }

  // Reason-code bitmask constants used by copyPrunedContext to track which caps fired.
  static final int REASON_MAX_CONTEXT_FIELDS = 1;
  static final int REASON_MAX_KEY_LENGTH = 1 << 1;
  static final int REASON_MAX_VALUE_LENGTH = 1 << 2;
  static final int REASON_MAX_LIST_ELEMENTS = 1 << 3;
  static final int REASON_MAX_STRUCTURE_PROPERTIES = 1 << 4;
  static final int REASON_MAX_SNAPSHOT_DEPTH = 1 << 5;
  static final int REASON_CYCLE = 1 << 6;

  /** Sorted reason-code strings, indexed by their bit position in the bitmask. */
  private static final String[] REASON_NAMES = {
    "max_context_fields",
    "max_key_length",
    "max_value_length",
    "max_list_elements",
    "max_structure_properties",
    "max_snapshot_depth",
    "cycle",
  };

  /**
   * Builds the sorted, comma-separated reason tag string from a bitmask of fired reason codes.
   * Returns null when no reason bit is set (no truncation occurred). The returned string is ready
   * to use directly as the "reason:..." tag value.
   */
  static String truncationReasonTag(final int reasonMask) {
    if (reasonMask == 0) {
      return null;
    }
    final StringBuilder sb = new StringBuilder();
    for (int bit = 0; bit < REASON_NAMES.length; bit++) {
      if ((reasonMask & (1 << bit)) != 0) {
        if (sb.length() > 0) {
          sb.append(',');
        }
        sb.append(REASON_NAMES[bit]);
      }
    }
    return sb.toString();
  }

  /**
   * Result of copyPrunedContext: the pruned attribute map plus an optional reason tag describing
   * which caps fired during the walk. truncatedReason is null when no truncation occurred, so
   * callers can skip the telemetry path with a single null check.
   */
  static final class CopyResult {
    final Map<String, Object> attrs;

    /** Conservative retained-byte estimate for attrs, calculated during the bounded copy. */
    final long estimatedRetainedBytes;

    /** Non-null when at least one cap fired; ready to use as the "reason:..." tag value. */
    final String truncatedReason;

    CopyResult(
        final Map<String, Object> attrs,
        final long estimatedRetainedBytes,
        final String truncatedReason) {
      this.attrs = attrs;
      this.estimatedRetainedBytes = estimatedRetainedBytes;
      this.truncatedReason = truncatedReason;
    }
  }

  /**
   * Single-pass bounded copy of a caller-owned EvaluationContext into the flattened, pruned map
   * stored on a FlagEvalEvent and later canonicalized by the aggregator.
   *
   * <p>Every retained-size dimension is capped inline so the hot path performs work proportional to
   * what is kept, never to what the caller supplied: MAX_CONTEXT_FIELDS - stop iterating the
   * top-level context past this many retained fields MAX_KEY_LENGTH - skip fields whose flattened
   * key exceeds this length (also enforced on every path segment produced by descending into
   * lists/structures) MAX_VALUE_LENGTH - skip string values exceeding this length MAX_LIST_ELEMENTS
   * - stop iterating a list past this many elements MAX_STRUCTURE_PROPERTIES - stop iterating a
   * structure past this many properties MAX_SNAPSHOT_DEPTH - stop descending into lists/structures
   * past this depth Cycle guard - identity-tracked containers currently on the recursion stack are
   * treated as leaves
   *
   * <p>All numeric limits are named constants so they can be tuned independently.
   *
   * <p>Returns a CopyResult whose attrs is an empty map for null/empty input and whose
   * truncatedReason is non-null when at least one cap fired. The returned map is a plain HashMap;
   * canonical-key sorting happens once in the aggregator, off the hot path.
   */
  static CopyResult copyPrunedContext(final EvaluationContext context) {
    if (context == null) {
      return new CopyResult(Collections.emptyMap(), 0, null);
    }
    final Set<String> keys = context.keySet();
    if (keys.isEmpty()) {
      return new CopyResult(Collections.emptyMap(), 0, null);
    }
    final HashMap<String, Object> out = new HashMap<>();
    final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    final CopyState state = new CopyState();
    for (final String key : keys) {
      if (out.size() >= MAX_CONTEXT_FIELDS) {
        state.reasonMask |= REASON_MAX_CONTEXT_FIELDS;
        break;
      }
      if (EvaluationContext.TARGETING_KEY.equals(key)) {
        continue;
      }
      copyPrunedValue(out, key, context.getValue(key), seen, 0, state);
    }
    final Map<String, Object> attrs = out.isEmpty() ? Collections.emptyMap() : out;
    return new CopyResult(
        attrs, state.estimatedRetainedBytes, truncationReasonTag(state.reasonMask));
  }

  private static void copyPrunedValue(
      final Map<String, Object> out,
      final String key,
      final Value value,
      final Set<Object> seen,
      final int depth,
      final CopyState state) {
    if (out.size() >= MAX_CONTEXT_FIELDS) {
      state.reasonMask |= REASON_MAX_CONTEXT_FIELDS;
      return;
    }
    if (key.length() > MAX_KEY_LENGTH) {
      state.reasonMask |= REASON_MAX_KEY_LENGTH;
      return;
    }
    if (value == null || value.isNull()) {
      putPrunedValue(out, key, null, state);
      return;
    }
    if (value.isString()) {
      final String s = value.asString();
      if (s.length() > MAX_VALUE_LENGTH) {
        state.reasonMask |= REASON_MAX_VALUE_LENGTH;
        return;
      }
      putPrunedValue(out, key, s, state);
      return;
    }
    if (value.isBoolean() || value.isNumber() || value.isInstant()) {
      putPrunedValue(out, key, convertValue(value), state);
      return;
    }
    if (value.isList()) {
      final List<Value> list = value.asList();
      if (depth >= MAX_SNAPSHOT_DEPTH) {
        state.reasonMask |= REASON_MAX_SNAPSHOT_DEPTH;
        return;
      }
      if (!seen.add(list)) {
        state.reasonMask |= REASON_CYCLE;
        return;
      }
      if (list.size() > MAX_LIST_ELEMENTS) {
        state.reasonMask |= REASON_MAX_LIST_ELEMENTS;
      }
      final int limit = Math.min(list.size(), MAX_LIST_ELEMENTS);
      for (int i = 0; i < limit; i++) {
        if (out.size() >= MAX_CONTEXT_FIELDS) {
          state.reasonMask |= REASON_MAX_CONTEXT_FIELDS;
          break;
        }
        copyPrunedValue(out, key + "[" + i + "]", list.get(i), seen, depth + 1, state);
      }
      seen.remove(list);
      return;
    }
    if (value.isStructure()) {
      final Structure structure = value.asStructure();
      if (depth >= MAX_SNAPSHOT_DEPTH) {
        state.reasonMask |= REASON_MAX_SNAPSHOT_DEPTH;
        return;
      }
      if (!seen.add(structure)) {
        state.reasonMask |= REASON_CYCLE;
        return;
      }
      int walked = 0;
      for (final String property : structure.keySet()) {
        if (walked >= MAX_STRUCTURE_PROPERTIES) {
          state.reasonMask |= REASON_MAX_STRUCTURE_PROPERTIES;
          break;
        }
        if (out.size() >= MAX_CONTEXT_FIELDS) {
          state.reasonMask |= REASON_MAX_CONTEXT_FIELDS;
          break;
        }
        walked++;
        copyPrunedValue(
            out, key + "." + property, structure.getValue(property), seen, depth + 1, state);
      }
      seen.remove(structure);
    }
  }

  private static void putPrunedValue(
      final Map<String, Object> out, final String key, final Object value, final CopyState state) {
    if (out.isEmpty()) {
      state.estimatedRetainedBytes += FlagEvalEventMemoryEstimator.contextMapRetainedBytes();
    }
    state.estimatedRetainedBytes +=
        FlagEvalEventMemoryEstimator.contextEntryRetainedBytes(key, value);
    out.put(key, value);
  }

  private static final class CopyState {
    private int reasonMask;
    private long estimatedRetainedBytes;
  }

  @FunctionalInterface
  private interface NumberComparator {
    boolean compare(double a, double b);
  }

  @FunctionalInterface
  private interface SemverComparator {
    boolean compare(int ordering);
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
