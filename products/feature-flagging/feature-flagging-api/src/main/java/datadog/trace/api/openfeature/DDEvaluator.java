package datadog.trace.api.openfeature;

import static java.util.Arrays.asList;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Flag;
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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
   * caller's evaluation thread over a caller-owned {@link Value} tree, so an arbitrarily deep
   * list/structure would overflow that thread's stack - and a {@code StackOverflowError} is not
   * caught by the {@code LinkageError | Exception} guards that keep telemetry from breaking an
   * evaluation. Values below the limit are truncated to null, the same way the cycle guard
   * truncates. Kept aligned with the cross-SDK RFC target (4).
   */
  static final int MAX_SNAPSHOT_DEPTH = 4;

  /**
   * Maximum number of top-level context fields retained by {@link #copyPrunedContext}. Bounds the
   * width of the caller-supplied context and, transitively, the size of every {@link FlagEvalEvent}
   * sitting in the async hand-off queue. Kept aligned with the cross-SDK RFC.
   */
  static final int MAX_CONTEXT_FIELDS = 256;

  /**
   * Maximum character length for a single context KEY retained by {@link #copyPrunedContext}. Keys
   * are stored verbatim in every full-tier bucket, so an unbounded key size would let a single
   * caller inflate steady-state heap use. Longer keys cause the field to be skipped.
   */
  static final int MAX_KEY_LENGTH = 256;

  /**
   * Maximum character length for a single context string VALUE retained by {@link
   * #copyPrunedContext}. Longer values cause the field to be skipped (matches previous {@code
   * pruneContext} behavior). Non-string scalars are not length-bounded.
   */
  static final int MAX_VALUE_LENGTH = 256;

  /**
   * Maximum number of elements walked per list encountered during {@link #copyPrunedContext}.
   * Bounds the fan-out of a single wide list at capture time so one caller cannot inflate the hot
   * path with a huge but shallow structure. Elements past the limit are skipped.
   */
  static final int MAX_LIST_ELEMENTS = 256;

  /**
   * Maximum number of properties walked per structure encountered during {@link
   * #copyPrunedContext}. Same intent as {@link #MAX_LIST_ELEMENTS} for structures. Properties past
   * the limit are skipped.
   */
  static final int MAX_STRUCTURE_PROPERTIES = 256;

  // Evaluation-metadata keys consumed by the span-enrichment capture hook (see
  // SpanEnrichmentHook). Emitted only when the span-enrichment gate is on.
  static final String METADATA_SPLIT_SERIAL_ID = "__dd_split_serial_id";
  static final String METADATA_DO_LOG = "__dd_do_log";

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
    try {
      final ServerConfiguration config = configuration.get();
      if (config == null) {
        return error(defaultValue, ErrorCode.PROVIDER_NOT_READY);
      }

      if (context == null) {
        return error(defaultValue, ErrorCode.INVALID_CONTEXT);
      }

      final Flag flag = config.flags.get(key);
      if (flag == null) {
        return error(defaultValue, ErrorCode.FLAG_NOT_FOUND);
      }

      if (!flag.enabled) {
        return ProviderEvaluation.<T>builder()
            .value(defaultValue)
            .reason(Reason.DISABLED.name())
            .build();
      }

      if (flag.allocations == null) {
        return error(defaultValue, ErrorCode.GENERAL, "Missing allocations for flag " + key);
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
                  target, key, defaultValue, flag, split.variationKey, allocation, split, context);
            } else {
              if (targetingKey == null) {
                return error(defaultValue, ErrorCode.TARGETING_KEY_MISSING);
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
                    context);
              }
            }
          }
        }
      }

      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(Reason.DEFAULT.name())
          .build();
    } catch (final PatternSyntaxException e) {
      return error(defaultValue, ErrorCode.PARSE_ERROR, e);
    } catch (final NumberFormatException e) {
      return error(defaultValue, ErrorCode.TYPE_MISMATCH, e);
    } catch (final Exception e) {
      return error(defaultValue, ErrorCode.GENERAL, e);
    }
  }

  private static <T> ProviderEvaluation<T> error(final T defaultValue, final ErrorCode code) {
    return error(defaultValue, code, (String) null);
  }

  private static <T> ProviderEvaluation<T> error(
      final T defaultValue, final ErrorCode code, final Throwable cause) {
    return error(defaultValue, code, cause == null ? null : cause.getMessage());
  }

  private static <T> ProviderEvaluation<T> error(
      final T defaultValue, final ErrorCode code, final String errorMessage) {
    return ProviderEvaluation.<T>builder()
        .value(defaultValue)
        .reason(Reason.ERROR.name())
        .errorCode(code)
        .errorMessage(errorMessage)
        .build();
  }

  private static boolean isEmpty(final List<?> list) {
    return list == null || list.isEmpty();
  }

  private static boolean isAllocationActive(final Allocation allocation, final Date now) {
    final Date startDate = allocation.startAt;
    if (startDate != null && now.before(startDate)) {
      return false;
    }

    final Date endDate = allocation.endAt;
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
    // PatternSyntaxException is intentionally not caught here so it propagates to evaluate(),
    // which maps it to ErrorCode.PARSE_ERROR.
    final Pattern pattern = Pattern.compile(String.valueOf(conditionValue));
    return pattern.matcher(String.valueOf(attributeValue)).find();
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

  private static <T> ProviderEvaluation<T> resolveVariant(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final Flag flag,
      final String variationKey,
      final Allocation allocation,
      final Split split,
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

    if (!isTypeCompatible(target, flag.variationType)) {
      return error(
          defaultValue,
          ErrorCode.TYPE_MISMATCH,
          "Requested type "
              + target.getSimpleName()
              + " does not match flag variationType "
              + flag.variationType.name());
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
              + e.getMessage());
    }

    // Stamp eval-time at the resolution point so first/last_evaluation reflect evaluation time,
    // not hook-fire time. Passed to the hook via provider metadata "dd.eval.timestamp_ms".
    final long evalTimestampMs = System.currentTimeMillis();
    final ImmutableMetadata.ImmutableMetadataBuilder metadataBuilder =
        ImmutableMetadata.builder()
            .addString("flagKey", flag.key)
            .addString("variationType", flag.variationType.name())
            .addString("allocationKey", allocation.key)
            .addLong("dd.eval.timestamp_ms", evalTimestampMs);
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

  /**
   * Single-pass bounded copy of a caller-owned {@link EvaluationContext} into the flattened, pruned
   * map stored on a {@link FlagEvalEvent} and later canonicalized by the aggregator.
   *
   * <p>Every retained-size dimension is capped inline so the hot path performs work proportional to
   * what is <em>kept</em>, never to what the caller supplied:
   *
   * <ul>
   *   <li>{@link #MAX_CONTEXT_FIELDS} - stop iterating the top-level context past this many
   *       retained fields
   *   <li>{@link #MAX_KEY_LENGTH} - skip fields whose flattened key exceeds this length (also
   *       enforced on every path segment produced by descending into lists/structures)
   *   <li>{@link #MAX_VALUE_LENGTH} - skip string values exceeding this length
   *   <li>{@link #MAX_LIST_ELEMENTS} - stop iterating a list past this many elements
   *   <li>{@link #MAX_STRUCTURE_PROPERTIES} - stop iterating a structure past this many properties
   *   <li>{@link #MAX_SNAPSHOT_DEPTH} - stop descending into lists/structures past this depth
   *   <li>Cycle guard - identity-tracked containers currently on the recursion stack are treated as
   *       leaves
   * </ul>
   *
   * <p>All numeric limits are named constants so they can be tuned independently.
   *
   * <p>Returns an empty map for null/empty input. The returned map is a plain {@link HashMap};
   * canonical-key sorting happens once in the aggregator, off the hot path.
   */
  static Map<String, Object> copyPrunedContext(final EvaluationContext context) {
    if (context == null) {
      return Collections.emptyMap();
    }
    final Set<String> keys = context.keySet();
    if (keys.isEmpty()) {
      return Collections.emptyMap();
    }
    final HashMap<String, Object> out = new HashMap<>();
    final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final String key : keys) {
      if (out.size() >= MAX_CONTEXT_FIELDS) {
        break;
      }
      if (EvaluationContext.TARGETING_KEY.equals(key)) {
        continue;
      }
      copyPrunedValue(out, key, context.getValue(key), seen, 0);
    }
    return out.isEmpty() ? Collections.emptyMap() : out;
  }

  private static void copyPrunedValue(
      final Map<String, Object> out,
      final String key,
      final Value value,
      final Set<Object> seen,
      final int depth) {
    if (out.size() >= MAX_CONTEXT_FIELDS) {
      return;
    }
    if (key.length() > MAX_KEY_LENGTH) {
      return;
    }
    if (value == null || value.isNull()) {
      out.put(key, null);
      return;
    }
    if (value.isString()) {
      final String s = value.asString();
      if (s.length() > MAX_VALUE_LENGTH) {
        return;
      }
      out.put(key, s);
      return;
    }
    if (value.isBoolean() || value.isNumber() || value.isInstant()) {
      out.put(key, convertValue(value));
      return;
    }
    if (value.isList()) {
      final List<Value> list = value.asList();
      if (depth >= MAX_SNAPSHOT_DEPTH || !seen.add(list)) {
        return;
      }
      final int limit = Math.min(list.size(), MAX_LIST_ELEMENTS);
      for (int i = 0; i < limit; i++) {
        if (out.size() >= MAX_CONTEXT_FIELDS) {
          break;
        }
        copyPrunedValue(out, key + "[" + i + "]", list.get(i), seen, depth + 1);
      }
      seen.remove(list);
      return;
    }
    if (value.isStructure()) {
      final Structure structure = value.asStructure();
      if (depth >= MAX_SNAPSHOT_DEPTH || !seen.add(structure)) {
        return;
      }
      int walked = 0;
      for (final String property : structure.keySet()) {
        if (walked >= MAX_STRUCTURE_PROPERTIES || out.size() >= MAX_CONTEXT_FIELDS) {
          break;
        }
        walked++;
        copyPrunedValue(out, key + "." + property, structure.getValue(property), seen, depth + 1);
      }
      seen.remove(structure);
    }
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
