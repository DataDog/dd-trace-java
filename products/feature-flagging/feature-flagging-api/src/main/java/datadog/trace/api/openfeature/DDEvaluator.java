package datadog.trace.api.openfeature;

import static java.util.Arrays.asList;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

class DDEvaluator implements Evaluator, FeatureFlaggingGateway.ConfigListener {

  private static final String STANDALONE_RUNTIME_CLASS =
      "com.datadog.featureflag.StandaloneFeatureFlaggingSystem";

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
  private final com.datadog.featureflag.core.FlagEvaluator core =
      new com.datadog.featureflag.core.FlagEvaluator(SPAN_ENRICHMENT_ENABLED);
  private final com.datadog.featureflag.core.ConfigurationStore configuration =
      new com.datadog.featureflag.core.ConfigurationStore();
  private volatile AutoCloseable standaloneRuntime;
  private boolean initialized;

  public DDEvaluator(final Runnable configCallback) {
    this.configCallback = configCallback;
  }

  @Override
  public synchronized boolean initialize(
      final long timeout, final TimeUnit unit, final EvaluationContext context) throws Exception {
    if (initialized) {
      return hasConfiguration();
    }
    initialized = true;
    try {
      try {
        FeatureFlaggingGateway.class.getMethod("activate");
      } catch (NoSuchMethodException incompatibleBridge) {
        throw new IllegalStateException(
            "The installed dd-java-agent bridge is incompatible with this dd-openfeature POC. "
                + "Use matching POC artifacts, or run standalone without dd-java-agent.",
            incompatibleBridge);
      }
      FeatureFlaggingGateway.addConfigListener(this);
      // Give an installed Java agent first refusal. Its activation listener claims AGENT
      // synchronously, which keeps transport, lifecycle, and span enrichment in the agent. With no
      // listener (the true standalone case), activation is a no-op and the bundled runtime claims
      // STANDALONE below.
      FeatureFlaggingGateway.activate();
      standaloneRuntime = startStandaloneRuntime();
      return configuration.await(timeout, unit) || hasConfiguration();
    } catch (final Exception | LinkageError exception) {
      shutdown();
      throw exception;
    }
  }

  @Override
  public boolean hasConfiguration() {
    return configuration.get() != null;
  }

  @Override
  public synchronized void shutdown() {
    initialized = false;
    FeatureFlaggingGateway.removeConfigListener(this);
    final AutoCloseable handle = standaloneRuntime;
    standaloneRuntime = null;
    if (handle != null) {
      try {
        handle.close();
      } catch (final Exception ignored) {
        // Shutdown must not interrupt application cleanup.
      }
    }
  }

  private static AutoCloseable startStandaloneRuntime() throws Exception {
    final Class<?> runtime;
    try {
      runtime = DDEvaluator.class.getClassLoader().loadClass(STANDALONE_RUNTIME_CLASS);
    } catch (final ClassNotFoundException ignored) {
      return null;
    }
    try {
      return (AutoCloseable) runtime.getMethod("acquire").invoke(null);
    } catch (InvocationTargetException failure) {
      final Throwable cause = failure.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw failure;
    }
  }

  static boolean invokeRuntime(final Class<?> runtime, final String methodName)
      throws ReflectiveOperationException {
    final Method method = runtime.getMethod(methodName);
    try {
      final Object result = method.invoke(null);
      return !(result instanceof Boolean) || (Boolean) result;
    } catch (final InvocationTargetException exception) {
      final Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw exception;
    }
  }

  @Override
  public void accept(final ServerConfiguration config) {
    configuration.set(config);
    if (config != null) {
      configuration.markReady();
      configCallback.run();
    } else if (configuration.wasReady()) {
      configCallback.run();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> ProviderEvaluation<T> evaluate(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    final com.datadog.featureflag.core.EvaluationContext adapted =
        context == null
            ? null
            : new com.datadog.featureflag.core.EvaluationContext() {
              @Override
              public String getTargetingKey() {
                return context.getTargetingKey();
              }

              @Override
              public boolean hasAttribute(final String name) {
                return context.keySet().contains(name);
              }

              @Override
              public Object attribute(final String name) {
                return context.convertValue(context.getValue(name));
              }
            };
    final com.datadog.featureflag.core.EvaluationResult<?> result =
        core.evaluate(configuration.get(), (Class) coreType(target), key, defaultValue, adapted);
    final ImmutableMetadata.ImmutableMetadataBuilder metadata = ImmutableMetadata.builder();
    for (final Map.Entry<String, Object> entry : result.getFlagMetadata().values().entrySet()) {
      final Object value = entry.getValue();
      if (value instanceof Boolean) metadata.addBoolean(entry.getKey(), (Boolean) value);
      else if (value instanceof Integer) metadata.addInteger(entry.getKey(), (Integer) value);
      else if (value instanceof Long) metadata.addLong(entry.getKey(), (Long) value);
      else metadata.addString(entry.getKey(), (String) value);
    }
    final T value =
        result.getValue() == defaultValue ? defaultValue : mapValue(target, result.getValue());
    final ProviderEvaluation<T> evaluation =
        ProviderEvaluation.<T>builder()
            .value(value)
            .reason(result.getReason())
            .variant(result.getVariant())
            .errorCode(
                result.getErrorCode() == null
                    ? null
                    : ErrorCode.valueOf(result.getErrorCode().name()))
            .errorMessage(result.getErrorMessage())
            .flagMetadata(metadata.build())
            .build();
    if (result.isLogExposure()) dispatchExposure(key, evaluation, context);
    return evaluation;
  }

  private static Class<?> coreType(final Class<?> target) {
    return target == Value.class ? Object.class : target;
  }

  static boolean isAllocationActive(final Allocation allocation, final Instant now) {
    return com.datadog.featureflag.core.FlagEvaluator.isAllocationActive(allocation, now);
  }

  static boolean isTypeCompatible(final Class<?> target, final ValueType type) {
    return com.datadog.featureflag.core.FlagEvaluator.isTypeCompatible(coreType(target), type);
  }

  @SuppressWarnings("unchecked")
  static <T> T mapValue(final Class<T> target, final Object value) {
    if (target == Value.class) {
      return value == null ? null : (T) Value.objectToValue(value);
    }
    return com.datadog.featureflag.core.FlagEvaluator.mapValue(target, value);
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

    /** Non-null when at least one cap fired; ready to use as the "reason:..." tag value. */
    final String truncatedReason;

    CopyResult(final Map<String, Object> attrs, final String truncatedReason) {
      this.attrs = attrs;
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
      return new CopyResult(Collections.emptyMap(), null);
    }
    final Set<String> keys = context.keySet();
    if (keys.isEmpty()) {
      return new CopyResult(Collections.emptyMap(), null);
    }
    final HashMap<String, Object> out = new HashMap<>();
    final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    final int[] reasonMask = {0};
    for (final String key : keys) {
      if (out.size() >= MAX_CONTEXT_FIELDS) {
        reasonMask[0] |= REASON_MAX_CONTEXT_FIELDS;
        break;
      }
      if (EvaluationContext.TARGETING_KEY.equals(key)) {
        continue;
      }
      copyPrunedValue(out, key, context.getValue(key), seen, 0, reasonMask);
    }
    final Map<String, Object> attrs = out.isEmpty() ? Collections.emptyMap() : out;
    return new CopyResult(attrs, truncationReasonTag(reasonMask[0]));
  }

  private static void copyPrunedValue(
      final Map<String, Object> out,
      final String key,
      final Value value,
      final Set<Object> seen,
      final int depth,
      final int[] reasonMask) {
    if (out.size() >= MAX_CONTEXT_FIELDS) {
      reasonMask[0] |= REASON_MAX_CONTEXT_FIELDS;
      return;
    }
    if (key.length() > MAX_KEY_LENGTH) {
      reasonMask[0] |= REASON_MAX_KEY_LENGTH;
      return;
    }
    if (value == null || value.isNull()) {
      out.put(key, null);
      return;
    }
    if (value.isString()) {
      final String s = value.asString();
      if (s.length() > MAX_VALUE_LENGTH) {
        reasonMask[0] |= REASON_MAX_VALUE_LENGTH;
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
      if (depth >= MAX_SNAPSHOT_DEPTH) {
        reasonMask[0] |= REASON_MAX_SNAPSHOT_DEPTH;
        return;
      }
      if (!seen.add(list)) {
        reasonMask[0] |= REASON_CYCLE;
        return;
      }
      if (list.size() > MAX_LIST_ELEMENTS) {
        reasonMask[0] |= REASON_MAX_LIST_ELEMENTS;
      }
      final int limit = Math.min(list.size(), MAX_LIST_ELEMENTS);
      for (int i = 0; i < limit; i++) {
        if (out.size() >= MAX_CONTEXT_FIELDS) {
          reasonMask[0] |= REASON_MAX_CONTEXT_FIELDS;
          break;
        }
        copyPrunedValue(out, key + "[" + i + "]", list.get(i), seen, depth + 1, reasonMask);
      }
      seen.remove(list);
      return;
    }
    if (value.isStructure()) {
      final Structure structure = value.asStructure();
      if (depth >= MAX_SNAPSHOT_DEPTH) {
        reasonMask[0] |= REASON_MAX_SNAPSHOT_DEPTH;
        return;
      }
      if (!seen.add(structure)) {
        reasonMask[0] |= REASON_CYCLE;
        return;
      }
      int walked = 0;
      for (final String property : structure.keySet()) {
        if (walked >= MAX_STRUCTURE_PROPERTIES) {
          reasonMask[0] |= REASON_MAX_STRUCTURE_PROPERTIES;
          break;
        }
        if (out.size() >= MAX_CONTEXT_FIELDS) {
          reasonMask[0] |= REASON_MAX_CONTEXT_FIELDS;
          break;
        }
        walked++;
        copyPrunedValue(
            out, key + "." + property, structure.getValue(property), seen, depth + 1, reasonMask);
      }
      seen.remove(structure);
    }
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
