package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.HAS_EVALUATION_FEATURES;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.OBSERVE_FULL_EVALUATION_DATA;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;

import com.datadog.openfeature.internal.FeatureFlagsRuntime;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.evaluation.ConfigurationStore;
import com.datadog.openfeature.internal.evaluation.FlagEvaluator;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.exposure.Subject;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationWriter;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.ValueType;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

class DDEvaluator implements Evaluator, Consumer<ServerConfiguration> {

  static final String DEPENDENCY_TELEMETRY_SCOPE = "datadog.dependencyTelemetryScope";

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

  private final Runnable configCallback;
  private final AgentBridge bridge;
  private final Settings settings;
  private final FlagEvaluator core;
  private final ConfigurationStore configuration = new ConfigurationStore();
  private volatile ConfigurationSnapshot configurationSnapshot = ConfigurationSnapshot.EMPTY;
  // Dependency telemetry is sidecar telemetry parallel to the existing OpenFeature client-hook
  // telemetry. The exposure hook pins one configuration snapshot before provider evaluation;
  // evaluation hooks publish the returned batch after graph completion and before the client call
  // returns; finallyAfter closes the scope even when application after hooks fail. Direct provider
  // calls have no scope and emit no sidecar telemetry. A stack isolates nested client evaluations.
  private final ThreadLocal<Deque<Object>> dependencyTelemetryScopes = new ThreadLocal<>();
  private volatile FeatureFlagsRuntime runtime;

  DDEvaluator(final Runnable configCallback, final AgentBridge bridge, final Settings settings) {
    this(configCallback, bridge, settings, ExperimentationOptOutMode.DISABLED);
  }

  DDEvaluator(
      final Runnable configCallback,
      final AgentBridge bridge,
      final Settings settings,
      final ExperimentationOptOutMode experimentationOptOutMode) {
    this.configCallback = configCallback;
    this.bridge = bridge;
    this.settings = settings;
    // When span enrichment is off, the __dd_* span-enrichment metadata is attached only to
    // evaluations of allocations logging exposures, for the exposure hook.
    this.core =
        new FlagEvaluator(
            settings.isSpanEnrichmentEnabled(), this::isExposed, experimentationOptOutMode);
  }

  @Override
  public boolean initialize(
      final long timeout, final TimeUnit unit, final EvaluationContext context) throws Exception {
    final FeatureFlagsRuntime acquired;
    synchronized (this) {
      if (this.runtime != null) {
        return hasConfiguration();
      }
      acquired = FeatureFlagsRuntime.acquire(this.bridge, this.settings);
      this.runtime = acquired;
      acquired.addConfigurationListener(this);
    }
    final boolean ready = this.configuration.await(timeout, unit) || hasConfiguration();
    return this.runtime == acquired && ready;
  }

  @Override
  public boolean hasConfiguration() {
    return this.configurationSnapshot.config != null;
  }

  @Override
  public synchronized void shutdown() {
    dependencyTelemetryScopes.remove();
    final FeatureFlagsRuntime released = this.runtime;
    this.runtime = null;
    if (released != null) {
      released.removeConfigurationListener(this);
      FeatureFlagsRuntime.release();
    }
  }

  /**
   * @return the runtime, or {@code null} if the evaluator is not initialized.
   */
  FeatureFlagsRuntime runtime() {
    return this.runtime;
  }

  @Override
  public void accept(final ServerConfiguration config) {
    final ConfigurationSnapshot snapshot = ConfigurationSnapshot.create(config);
    this.configurationSnapshot = snapshot;
    this.configuration.set(config);
    if (config != null) {
      this.configuration.markReady();
      this.configCallback.run();
    } else if (this.configuration.wasReady()) {
      this.configCallback.run();
    }
  }

  @Override
  public <T> ProviderEvaluation<T> evaluate(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    final Object scope = claimEvaluationScope(key, context);
    final DependencyTelemetryToken token =
        scope instanceof DependencyTelemetryToken ? (DependencyTelemetryToken) scope : null;
    final ConfigurationSnapshot snapshot =
        token != null
            ? token.snapshot
            : scope instanceof EvaluationScope
                ? ((EvaluationScope) scope).snapshot
                : configurationSnapshot;
    final FlagEvaluator.EvaluationResult<T> evaluation =
        core.evaluateWithTrace(snapshot.config, target, key, defaultValue, context);
    final ProviderEvaluation<T> result = evaluation.details;
    stageDependencyTelemetry(token, evaluation);
    return result;
  }

  static boolean isAllocationActive(final Allocation allocation, final Instant now) {
    return FlagEvaluator.isAllocationActive(allocation, now);
  }

  static boolean isTypeCompatible(final Class<?> target, final ValueType type) {
    return FlagEvaluator.isTypeCompatible(target, type);
  }

  static <T> T mapValue(final Class<T> target, final Object value) {
    return FlagEvaluator.mapValue(target, value);
  }

  private boolean isExposed(
      final String flag,
      final String subject,
      final String allocation,
      final String variant,
      final Integer serialId) {
    final ExposureDeduplicationCache cache = exposureCache();
    return cache != null && cache.contains(flag, subject, allocation, variant, serialId);
  }

  /**
   * Gets the exposure deduplication cache of the runtime.
   *
   * @return the cache, or {@code null} if the evaluator is not initialized.
   */
  ExposureDeduplicationCache exposureCache() {
    final FeatureFlagsRuntime current = this.runtime;
    return current == null ? null : current.exposureCache();
  }

  /**
   * Tells whether exposures are sent to Datadog.
   *
   * @return whether the evaluator is initialized with exposure delivery enabled.
   */
  boolean isExposureDeliveryEnabled() {
    final FeatureFlagsRuntime current = this.runtime;
    return current != null && current.isExposureDeliveryEnabled();
  }

  /**
   * Builds the exposure of an evaluation.
   *
   * @param flag the flag key.
   * @param allocationKey the allocation key.
   * @param variantKey the variant key.
   * @param context the evaluation context.
   * @param serialId the split serial id, or {@code null} if absent.
   * @param features the split features sent with the exposure, possibly empty.
   * @return the exposure event.
   */
  static ExposureEvent exposureEvent(
      final String flag,
      final String allocationKey,
      final String variantKey,
      final EvaluationContext context,
      final Integer serialId,
      final Map<String, Object> features) {
    return new ExposureEvent(
        System.currentTimeMillis(),
        new com.datadog.openfeature.internal.exposure.Allocation(allocationKey),
        new com.datadog.openfeature.internal.exposure.Flag(flag),
        new com.datadog.openfeature.internal.exposure.Variant(variantKey),
        new Subject(context.getTargetingKey(), flattenContext(context)),
        serialId,
        features.isEmpty() ? null : features);
  }

  private static ExposureEvent exposureEvent(
      final String flag,
      final String allocationKey,
      final String variantKey,
      final String targetingKey,
      final Map<String, Object> contextAttributes,
      final Integer serialId,
      final Map<String, Object> features) {
    return new ExposureEvent(
        System.currentTimeMillis(),
        new com.datadog.openfeature.internal.exposure.Allocation(allocationKey),
        new com.datadog.openfeature.internal.exposure.Flag(flag),
        new com.datadog.openfeature.internal.exposure.Variant(variantKey),
        new Subject(targetingKey, contextAttributes),
        serialId,
        features.isEmpty() ? null : features);
  }

  /**
   * Records an exposure to the runtime, if initialized.
   *
   * @param event the exposure to record.
   */
  void recordExposure(final ExposureEvent event) {
    final FeatureFlagsRuntime current = this.runtime;
    if (current != null) {
      current.recordExposure(event);
    }
  }

  void publishDependencyEvaluation(
      final FlagEvaluator.DependencyEvaluation dependency,
      final EvaluationContext context,
      final long evaluationTimestampMs,
      final FlagEvaluationWriter writer,
      final DependencyContextSnapshot contextSnapshot) {
    final ImmutableMetadata metadata = dependency.metadata;
    final Map<String, Object> values =
        metadata == null ? Collections.<String, Object>emptyMap() : metadata.asUnmodifiableMap();
    final Object allocationValue = values.get(ALLOCATION_KEY);
    final String allocationKey =
        allocationValue instanceof String ? (String) allocationValue : null;
    if (writer == null || !writer.hasCapacityForEnqueue()) {
      if (writer != null) {
        writer.countPreQueueOverflow();
      }
      return;
    }
    final boolean observeFullEvaluationData =
        Boolean.TRUE.equals(values.get(OBSERVE_FULL_EVALUATION_DATA));
    String errorMessage = observeFullEvaluationData ? dependency.errorMessage : null;
    if ((errorMessage == null || errorMessage.isEmpty()) && dependency.errorCode != null) {
      errorMessage = dependency.errorCode.name();
    }
    final Map<String, Object> attrs;
    if (observeFullEvaluationData) {
      attrs = contextSnapshot.capture(context, writer);
    } else {
      attrs = Collections.emptyMap();
    }
    final Map<String, Object> features =
        Boolean.TRUE.equals(values.get(HAS_EVALUATION_FEATURES))
            ? SplitFeatureMetadata.featuresWithPrefix(metadata, EVALUATION_FEATURE_PREFIX)
            : Collections.<String, Object>emptyMap();
    writer.enqueue(
        new FlagEvalEvent(
            dependency.flagKey,
            dependency.variant,
            allocationKey,
            context == null ? null : context.getTargetingKey(),
            errorMessage,
            evaluationTimestampMs,
            observeFullEvaluationData,
            attrs,
            features));
  }

  void publishDependencyEvaluations(
      final DependencyEvaluationTrace trace,
      final EvaluationContext context,
      final FlagEvaluationWriter writer) {
    final DependencyContextSnapshot contextSnapshot = new DependencyContextSnapshot();
    for (final FlagEvaluator.DependencyEvaluation dependency : trace.evaluations) {
      try {
        publishDependencyEvaluation(
            dependency, context, trace.evaluationTimestampMs, writer, contextSnapshot);
      } catch (final RuntimeException | LinkageError ignored) {
        // One telemetry record must not suppress the root or later dependency records.
      }
    }
  }

  /** Lazily captures one detached evaluation-context snapshot for a dependency batch. */
  private static final class DependencyContextSnapshot {
    private CopyResult copy;

    private Map<String, Object> capture(
        final EvaluationContext context, final FlagEvaluationWriter writer) {
      if (copy == null) {
        copy = copyPrunedContext(context);
        if (copy.truncatedReason != null) {
          writer.countContextTruncated(copy.truncatedReason);
        }
      }
      return copy.attrs;
    }
  }

  void recordDependencyMetrics(
      final DependencyEvaluationTrace trace, final FlagEvalMetrics metrics) {
    if (metrics == null) {
      return;
    }
    for (final FlagEvaluator.DependencyEvaluation dependency : trace.evaluations) {
      try {
        final ImmutableMetadata metadata = dependency.metadata;
        final Map<String, Object> values =
            metadata == null
                ? Collections.<String, Object>emptyMap()
                : metadata.asUnmodifiableMap();
        final Object allocationValue = values.get(ALLOCATION_KEY);
        metrics.record(
            dependency.flagKey,
            dependency.variant,
            dependency.reason,
            dependency.errorCode,
            allocationValue instanceof String ? (String) allocationValue : null);
      } catch (final RuntimeException | LinkageError ignored) {
        // Metrics are best effort and must not affect evaluation or the root metric.
      }
    }
  }

  Object beginDependencyExposureScope(
      final String rootFlagKey, final EvaluationContext evaluationContext) {
    final ConfigurationSnapshot snapshot = configurationSnapshot;
    final Object scope =
        snapshot.dependencyFlags.contains(rootFlagKey)
            ? new DependencyTelemetryToken(snapshot, rootFlagKey, evaluationContext)
            : new EvaluationScope(snapshot, rootFlagKey, evaluationContext);
    Deque<Object> scopes = dependencyTelemetryScopes.get();
    if (scopes == null) {
      scopes = new ArrayDeque<>();
      dependencyTelemetryScopes.set(scopes);
    }
    scopes.push(scope);
    return scope;
  }

  List<FlagEvaluator.DependencyExposure> endDependencyExposureScope(final Object scope) {
    final Deque<Object> scopes = dependencyTelemetryScopes.get();
    if (scope == null || scopes == null || scopes.isEmpty() || scopes.peek() != scope) {
      return Collections.emptyList();
    }
    scopes.pop();
    if (scopes.isEmpty()) {
      dependencyTelemetryScopes.remove();
    }
    return scope instanceof DependencyTelemetryToken
        ? ((DependencyTelemetryToken) scope).exposures
        : Collections.<FlagEvaluator.DependencyExposure>emptyList();
  }

  List<FlagEvaluator.DependencyExposure> endDependencyExposureScope() {
    final Deque<Object> scopes = dependencyTelemetryScopes.get();
    return scopes == null || scopes.isEmpty()
        ? Collections.<FlagEvaluator.DependencyExposure>emptyList()
        : endDependencyExposureScope(scopes.peek());
  }

  DependencyTelemetryToken dependencyTelemetryToken(final Object scope) {
    return scope instanceof DependencyTelemetryToken && ((DependencyTelemetryToken) scope).sealed
        ? (DependencyTelemetryToken) scope
        : null;
  }

  Object currentDependencyTelemetryScope() {
    final Deque<Object> scopes = dependencyTelemetryScopes.get();
    return scopes == null || scopes.isEmpty() ? null : scopes.peek();
  }

  DependencyEvaluationTrace dependencyEvaluationTrace(final DependencyTelemetryToken token) {
    final Deque<Object> scopes = dependencyTelemetryScopes.get();
    if (token == null || scopes == null || scopes.isEmpty() || scopes.peek() != token) {
      return DependencyEvaluationTrace.EMPTY;
    }
    return token.sealed ? token.evaluationTrace : DependencyEvaluationTrace.EMPTY;
  }

  private Object claimEvaluationScope(
      final String rootFlagKey, final EvaluationContext evaluationContext) {
    final Deque<Object> scopes = dependencyTelemetryScopes.get();
    if (scopes == null || scopes.isEmpty()) {
      return null;
    }
    final Object scope = scopes.peek();
    if (scope instanceof DependencyTelemetryToken) {
      final DependencyTelemetryToken token = (DependencyTelemetryToken) scope;
      if (token.claimed || !token.matches(rootFlagKey, evaluationContext)) {
        return null;
      }
      token.claimed = true;
      return token;
    }
    if (!(scope instanceof EvaluationScope)) {
      return null;
    }
    final EvaluationScope evaluationScope = (EvaluationScope) scope;
    if (evaluationScope.claimed || !evaluationScope.matches(rootFlagKey, evaluationContext)) {
      return null;
    }
    evaluationScope.claimed = true;
    return evaluationScope;
  }

  private void stageDependencyTelemetry(
      final DependencyTelemetryToken token, final FlagEvaluator.EvaluationResult<?> evaluation) {
    if (token == null || token.sealed) {
      return;
    }
    // The first matching provider evaluation owns this hook lifecycle, even when it has no
    // eligible candidates. This prevents a later direct or reentrant evaluation from filling the
    // scope before finallyAfter consumes it.
    token.sealed = true;
    if (!evaluation.dependencyEvaluations.isEmpty()) {
      token.evaluationTrace =
          new DependencyEvaluationTrace(
              evaluation.dependencyEvaluations, evaluation.evaluationTimestampMs);
    }
    final List<FlagEvaluator.DependencyExposure> candidates =
        evaluation.details.getErrorCode() == null
            ? evaluation.dependencyExposures
            : Collections.<FlagEvaluator.DependencyExposure>emptyList();
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    List<FlagEvaluator.DependencyExposure> eligible = null;
    for (final FlagEvaluator.DependencyExposure candidate : candidates) {
      final ImmutableMetadata metadata = candidate.metadata;
      final Map<String, Object> values =
          metadata == null ? Collections.<String, Object>emptyMap() : metadata.asUnmodifiableMap();
      if (!Boolean.TRUE.equals(values.get(DO_LOG))
          || !(values.get(ALLOCATION_KEY) instanceof String)
          || candidate.variant == null) {
        continue;
      }
      if (eligible == null) {
        eligible = new ArrayList<>();
      }
      eligible.add(candidate);
    }
    if (eligible != null) {
      token.exposures = Collections.unmodifiableList(eligible);
    }
  }

  List<FlagEvaluationDetails<Object>> recordDependencyExposures(
      final List<FlagEvaluator.DependencyExposure> exposures, final EvaluationContext context) {
    final ExposureDeduplicationCache cache = exposureCache();
    if (cache == null || context == null) {
      return Collections.emptyList();
    }
    final String targetingKey = context.getTargetingKey();
    final boolean exposureDeliveryEnabled = isExposureDeliveryEnabled();
    Map<String, Object> contextAttributes = null;
    List<FlagEvaluationDetails<Object>> details = null;
    for (final FlagEvaluator.DependencyExposure exposure : exposures) {
      final ImmutableMetadata metadata = exposure.metadata;
      final Map<String, Object> values =
          metadata == null ? Collections.<String, Object>emptyMap() : metadata.asUnmodifiableMap();
      if (!Boolean.TRUE.equals(values.get(DO_LOG))) {
        continue;
      }
      final Object allocationValue = values.get(ALLOCATION_KEY);
      final String allocationKey =
          allocationValue instanceof String ? (String) allocationValue : null;
      final String variantKey = exposure.variant;
      if (allocationKey == null || variantKey == null) {
        continue;
      }
      if (details == null) {
        details = new ArrayList<>();
      }
      details.add(
          FlagEvaluationDetails.<Object>builder()
              .flagKey(exposure.flagKey)
              .value(exposure.value)
              .variant(variantKey)
              .reason(exposure.reason)
              .flagMetadata(metadata)
              .build());
      final Object serialValue = values.get(SPLIT_SERIAL_ID);
      final Integer serialId = serialValue instanceof Integer ? (Integer) serialValue : null;
      if (cache.recordIfChanged(exposure.flagKey, targetingKey, allocationKey, variantKey, serialId)
          && exposureDeliveryEnabled) {
        if (contextAttributes == null) {
          contextAttributes = Collections.unmodifiableMap(flattenContext(context));
        }
        recordExposure(
            exposureEvent(
                exposure.flagKey,
                allocationKey,
                variantKey,
                targetingKey,
                contextAttributes,
                serialId,
                SplitFeatureMetadata.featuresWithPrefix(metadata, EXPOSURE_FEATURE_PREFIX)));
      }
    }
    return details == null ? Collections.emptyList() : Collections.unmodifiableList(details);
  }

  static final class DependencyEvaluationTrace {
    private static final DependencyEvaluationTrace EMPTY =
        new DependencyEvaluationTrace(Collections.emptyList(), 0);

    final List<FlagEvaluator.DependencyEvaluation> evaluations;
    final long evaluationTimestampMs;

    private DependencyEvaluationTrace(
        final List<FlagEvaluator.DependencyEvaluation> evaluations,
        final long evaluationTimestampMs) {
      this.evaluations = evaluations;
      this.evaluationTimestampMs = evaluationTimestampMs;
    }
  }

  static final class DependencyTelemetryToken {
    private final ConfigurationSnapshot snapshot;
    private final String rootFlagKey;
    private final EvaluationContext evaluationContext;
    private DependencyEvaluationTrace evaluationTrace = DependencyEvaluationTrace.EMPTY;
    private List<FlagEvaluator.DependencyExposure> exposures = Collections.emptyList();
    private boolean claimed;
    private boolean sealed;

    private DependencyTelemetryToken(
        final ConfigurationSnapshot snapshot,
        final String rootFlagKey,
        final EvaluationContext evaluationContext) {
      this.snapshot = snapshot;
      this.rootFlagKey = rootFlagKey;
      this.evaluationContext = evaluationContext;
    }

    private boolean matches(
        final String evaluatedFlagKey, final EvaluationContext evaluatedContext) {
      return rootFlagKey != null
          && rootFlagKey.equals(evaluatedFlagKey)
          && evaluationContext == evaluatedContext;
    }
  }

  private static final class EvaluationScope {
    private final ConfigurationSnapshot snapshot;
    private final String rootFlagKey;
    private final EvaluationContext evaluationContext;
    private boolean claimed;

    private EvaluationScope(
        final ConfigurationSnapshot snapshot,
        final String rootFlagKey,
        final EvaluationContext evaluationContext) {
      this.snapshot = snapshot;
      this.rootFlagKey = rootFlagKey;
      this.evaluationContext = evaluationContext;
    }

    private boolean matches(
        final String evaluatedFlagKey, final EvaluationContext evaluatedContext) {
      return rootFlagKey != null
          && rootFlagKey.equals(evaluatedFlagKey)
          && evaluationContext == evaluatedContext;
    }
  }

  private static final class ConfigurationSnapshot {
    private static final ConfigurationSnapshot EMPTY =
        new ConfigurationSnapshot(null, Collections.<String>emptySet());

    private final ServerConfiguration config;
    private final Set<String> dependencyFlags;

    private ConfigurationSnapshot(
        final ServerConfiguration config, final Set<String> dependencyFlags) {
      this.config = config;
      this.dependencyFlags = dependencyFlags;
    }

    private static ConfigurationSnapshot create(final ServerConfiguration config) {
      if (config == null || config.flags == null || config.flags.isEmpty()) {
        return config == null ? EMPTY : new ConfigurationSnapshot(config, Collections.emptySet());
      }
      final Set<String> dependencyFlags = new HashSet<>();
      for (final Map.Entry<String, Flag> entry : config.flags.entrySet()) {
        final Flag flag = entry.getValue();
        if (flag == null || flag.allocations == null) {
          continue;
        }
        boolean found = false;
        for (final Allocation allocation : flag.allocations) {
          if (allocation == null || allocation.rules == null) {
            continue;
          }
          for (final Rule rule : allocation.rules) {
            if (rule == null || rule.conditions == null) {
              continue;
            }
            for (final ConditionConfiguration condition : rule.conditions) {
              if (condition != null && condition.flagEvaluationKey != null) {
                dependencyFlags.add(entry.getKey());
                found = true;
                break;
              }
            }
            if (found) {
              break;
            }
          }
          if (found) {
            break;
          }
        }
      }
      return new ConfigurationSnapshot(config, Collections.unmodifiableSet(dependencyFlags));
    }
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
      final Deque<FlattenEntry> deque = new ArrayDeque<>();
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
