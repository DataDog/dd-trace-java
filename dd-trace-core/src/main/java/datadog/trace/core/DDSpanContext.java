package datadog.trace.core;

import static datadog.trace.api.DDTags.PARENT_ID;
import static datadog.trace.api.DDTags.SPAN_EVENTS;
import static datadog.trace.api.DDTags.SPAN_LINKS;
import static datadog.trace.bootstrap.instrumentation.api.ErrorPriorities.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.MANUAL;
import static datadog.trace.core.CoreSpan.OWN_PROPAGATION_TAGS;
import static datadog.trace.core.CoreSpan.ROOT_PROPAGATION_TAGS;
import static datadog.trace.core.CoreSpan.STRUCTURED_EVENTS;
import static datadog.trace.core.CoreSpan.STRUCTURED_LINKS;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.Functions;
import datadog.trace.api.HashStore;
import datadog.trace.api.IdStore;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.TagRouting;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.llmobs.LLMObsPropagationValues;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.taginterceptor.TagInterceptor;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.util.TagsHelper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SpanContext represents Span state that must propagate to descendant Spans and across process
 * boundaries.
 *
 * <p>SpanContext is logically divided into two pieces: (1) the user-level "Baggage" that propagates
 * across Span boundaries and (2) any Datadog fields that are needed to identify or contextualize
 * the associated Span instance
 */
public class DDSpanContext
    implements AgentSpanContext, RequestContext, TraceSegment, ProfilerContext {
  private static final Logger log = LoggerFactory.getLogger(DDSpanContext.class);

  public static final String PRIORITY_SAMPLING_KEY = "_sampling_priority_v1";
  public static final String SAMPLE_RATE_KEY = "_sample_rate";

  public static final String SPAN_SAMPLING_MECHANISM_TAG = "_dd.span_sampling.mechanism";
  public static final String SPAN_SAMPLING_RULE_RATE_TAG = "_dd.span_sampling.rule_rate";
  public static final String SPAN_SAMPLING_MAX_PER_SECOND_TAG = "_dd.span_sampling.max_per_second";

  private static final UTF8BytesString OTLP_EXPORT_TRUE = UTF8BytesString.create("true");
  private static final UTF8BytesString OTLP_EXPORT_FALSE = UTF8BytesString.create("false");

  private static final DDCache<String, UTF8BytesString> THREAD_NAMES =
      DDCaches.newFixedSizeCache(256);

  private static final Map<String, String> EMPTY_BAGGAGE = Collections.emptyMap();
  private static final Map<String, Object> EMPTY_META_STRUCT = Collections.emptyMap();

  /** The collection of all span related to this one */
  private final TraceCollector traceCollector;

  private final TagInterceptor tagInterceptor;

  /** Baggage is associated with the whole trace and shared with other spans */
  private volatile Map<String, String> baggageItems;

  private final Baggage w3cBaggage;

  // Not Shared with other span contexts
  private final DDTraceId traceId;
  private final long spanId;
  private final long parentId;

  private final String parentServiceName;

  private final long threadId;
  private final UTF8BytesString threadName;

  private volatile short httpStatusCode;

  // Cached span.kind ordinal for fast isOutbound() checks.
  // Ordinal constants -- keep in sync with SPAN_KIND_VALUES array.
  static final byte SPAN_KIND_UNSET = 0;
  static final byte SPAN_KIND_SERVER = 1;
  static final byte SPAN_KIND_CLIENT = 2;
  static final byte SPAN_KIND_PRODUCER = 3;
  static final byte SPAN_KIND_CONSUMER = 4;
  static final byte SPAN_KIND_INTERNAL = 5;
  static final byte SPAN_KIND_BROKER = 6;
  static final byte SPAN_KIND_CUSTOM = 7;

  /** Maps ordinal to canonical string constant. Index 0 (UNSET) and 7 (CUSTOM) are null. */
  static final String[] SPAN_KIND_VALUES = {
    null, // UNSET
    Tags.SPAN_KIND_SERVER,
    Tags.SPAN_KIND_CLIENT,
    Tags.SPAN_KIND_PRODUCER,
    Tags.SPAN_KIND_CONSUMER,
    Tags.SPAN_KIND_INTERNAL,
    Tags.SPAN_KIND_BROKER,
    null // CUSTOM
  };

  private volatile byte spanKindOrdinal = SPAN_KIND_UNSET;

  private CharSequence integrationName;
  private CharSequence serviceNameSource;

  /**
   * Tags are associated to the current span, they will not propagate to the children span.
   *
   * <p>The underlying assumption for using a normal Map with synchronized access instead of a
   * ConcurrentHashMap is that even though the tags can be accessed and modified from multiple
   * threads, they will rarely, if ever, be read and modified concurrently by multiple threads but
   * rather read and accessed in a serial fashion on thread after thread. The synchronization can
   * then be wrapped around bulk operations to minimize the costly atomic operations.
   *
   * <p>The span owns its tag stores directly: {@code tagIds}, the known tags by id, created with
   * the span; and {@code customTags}, the custom tags by name, created on the first custom write --
   * until then null, or the read-through parent's custom store, borrowed read-only while {@code
   * customTagsBorrowed}. Every read and write goes span to store, routed by {@link TagRouting},
   * under one lock that guards both: {@code tagIds}, which always exists. Internal readers get a
   * cached {@link #tagView() view}.
   */
  private final IdStore tagIds;

  private HashStore customTags;

  private boolean customTagsBorrowed;

  /**
   * This span's view of its tag stores for its internal readers, created on first use under the
   * {@code tagIds} lock; see {@link #tagView()}.
   */
  private TagMap tagView;

  /** The service name is required, otherwise the span are dropped by the agent */
  private volatile String serviceName;

  /** The resource associated to the service (server_web, database, etc.) */
  private volatile CharSequence resourceName;

  private volatile byte resourceNamePriority = ResourceNamePriorities.DEFAULT;

  /** Each span have an operation name describing the current span */
  private volatile CharSequence operationName;

  /** The type of the span. If null, the Datadog Agent will report as a custom */
  private volatile CharSequence spanType;

  /** True indicates that the span reports an error */
  private volatile boolean errorFlag;

  private volatile byte errorFlagPriority = UNSET;

  private volatile boolean measured;

  private volatile boolean topLevel;

  /** The origin of the trace. (eg. Synthetics, CI App) */
  private volatile CharSequence origin;

  /** RequestContext data for the InstrumentationGateway */
  private final Object requestContextDataAppSec;

  private final Object requestContextDataIast;
  private final Object ciVisibilityContextData;

  private final boolean disableSamplingMechanismValidation;

  private final PropagationTags propagationTags;

  private volatile PathwayContext pathwayContext;

  private volatile BlockResponseFunction blockResponseFunction;

  private volatile ClientIpAddressData clientIpAddressData;

  private final ProfilingContextIntegration profilingContextIntegration;
  private final boolean injectBaggageAsTags;
  private final boolean injectLinksAsTags;
  private volatile int encodedOperationName;
  private volatile int encodedResourceName;

  /**
   * Metastruct keys are associated to the current span, they will not propagate to the children
   * span. They are an efficient way to send binary data to the agent without relying on bulky json
   * payloads inside tags.
   */
  private volatile Map<String, Object> metaStruct = EMPTY_META_STRUCT;

  public DDSpanContext(
      final DDTraceId traceId,
      final long spanId,
      final long parentId,
      final CharSequence parentServiceName,
      final String serviceName,
      final CharSequence operationName,
      final CharSequence resourceName,
      final int samplingPriority,
      final CharSequence origin,
      final Map<String, String> baggageItems,
      final boolean errorFlag,
      final CharSequence spanType,
      final int tagsSize,
      final TraceCollector traceCollector,
      final Object requestContextDataAppSec,
      final Object requestContextDataIast,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation,
      final PropagationTags propagationTags) {
    this(
        traceId,
        spanId,
        parentId,
        parentServiceName,
        null,
        serviceName,
        operationName,
        resourceName,
        samplingPriority,
        origin,
        baggageItems,
        null,
        errorFlag,
        spanType,
        tagsSize,
        traceCollector,
        requestContextDataAppSec,
        requestContextDataIast,
        null,
        pathwayContext,
        disableSamplingMechanismValidation,
        propagationTags,
        ProfilingContextIntegration.NoOp.INSTANCE,
        true,
        true,
        null);
  }

  public DDSpanContext(
      final DDTraceId traceId,
      final long spanId,
      final long parentId,
      final CharSequence parentServiceName,
      final String serviceName,
      final CharSequence operationName,
      final CharSequence resourceName,
      final int samplingPriority,
      final CharSequence origin,
      final Map<String, String> baggageItems,
      final boolean errorFlag,
      final CharSequence spanType,
      final int tagsSize,
      final TraceCollector traceCollector,
      final Object requestContextDataAppSec,
      final Object requestContextDataIast,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation,
      final PropagationTags propagationTags,
      final boolean injectBaggageAsTags,
      final boolean injectLinksAsTags) {
    this(
        traceId,
        spanId,
        parentId,
        parentServiceName,
        null,
        serviceName,
        operationName,
        resourceName,
        samplingPriority,
        origin,
        baggageItems,
        null,
        errorFlag,
        spanType,
        tagsSize,
        traceCollector,
        requestContextDataAppSec,
        requestContextDataIast,
        null,
        pathwayContext,
        disableSamplingMechanismValidation,
        propagationTags,
        ProfilingContextIntegration.NoOp.INSTANCE,
        injectBaggageAsTags,
        injectLinksAsTags,
        null);
  }

  /** Back-compat ctor (no read-through parent); delegates with a null parent. */
  public DDSpanContext(
      final DDTraceId traceId,
      final long spanId,
      final long parentId,
      final CharSequence parentServiceName,
      final CharSequence serviceNameSource,
      final String serviceName,
      final CharSequence operationName,
      final CharSequence resourceName,
      final int samplingPriority,
      final CharSequence origin,
      final Map<String, String> baggageItems,
      final Baggage w3cBaggage,
      final boolean errorFlag,
      final CharSequence spanType,
      final int tagsSize,
      final TraceCollector traceCollector,
      final Object requestContextDataAppSec,
      final Object requestContextDataIast,
      final Object CiVisibilityContextData,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation,
      final PropagationTags propagationTags,
      final ProfilingContextIntegration profilingContextIntegration,
      final boolean injectBaggageAsTags,
      final boolean injectLinksAsTags) {
    this(
        traceId,
        spanId,
        parentId,
        parentServiceName,
        serviceNameSource,
        serviceName,
        operationName,
        resourceName,
        samplingPriority,
        origin,
        baggageItems,
        w3cBaggage,
        errorFlag,
        spanType,
        tagsSize,
        traceCollector,
        requestContextDataAppSec,
        requestContextDataIast,
        CiVisibilityContextData,
        pathwayContext,
        disableSamplingMechanismValidation,
        propagationTags,
        profilingContextIntegration,
        injectBaggageAsTags,
        injectLinksAsTags,
        null);
  }

  public DDSpanContext(
      final DDTraceId traceId,
      final long spanId,
      final long parentId,
      final CharSequence parentServiceName,
      final CharSequence serviceNameSource,
      final String serviceName,
      final CharSequence operationName,
      final CharSequence resourceName,
      final int samplingPriority,
      final CharSequence origin,
      final Map<String, String> baggageItems,
      final Baggage w3cBaggage,
      final boolean errorFlag,
      final CharSequence spanType,
      final int tagsSize,
      final TraceCollector traceCollector,
      final Object requestContextDataAppSec,
      final Object requestContextDataIast,
      final Object CiVisibilityContextData,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation,
      final PropagationTags propagationTags,
      final ProfilingContextIntegration profilingContextIntegration,
      final boolean injectBaggageAsTags,
      final boolean injectLinksAsTags,
      final TagMap readThroughParent) {

    assert traceCollector != null;
    this.traceCollector = traceCollector;
    this.tagInterceptor = this.traceCollector.getTracer().getTagInterceptor();

    assert traceId != null;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;
    this.parentServiceName = String.valueOf(parentServiceName);

    if (baggageItems == null || baggageItems.isEmpty()) {
      this.baggageItems = EMPTY_BAGGAGE;
    } else {
      this.baggageItems = new ConcurrentHashMap<>(baggageItems);
    }
    this.w3cBaggage = w3cBaggage;

    this.requestContextDataAppSec = requestContextDataAppSec;
    this.requestContextDataIast = requestContextDataIast;
    this.ciVisibilityContextData = CiVisibilityContextData;

    assert pathwayContext != null;
    this.pathwayContext = pathwayContext;

    // Reads through to the frozen tracer-level tags, if any.
    TagMap parentTags = TagRouting.readThroughParent(readThroughParent);
    this.tagIds = TagRouting.newIdStore(parentTags);
    this.customTags = TagRouting.inheritedCustomStore(parentTags);
    this.customTagsBorrowed = this.customTags != null;

    // must set this before setting the service and resource names below
    this.profilingContextIntegration = profilingContextIntegration;
    // as fast as we can try to make this operation, we still might need to activate/deactivate
    // contexts at alarming rates in unpredictable async applications, so we'll try
    // to get away with doing this just once per span
    this.encodedOperationName = profilingContextIntegration.encodeOperationName(operationName);

    internalSetServiceName(serviceName);
    this.serviceNameSource = serviceNameSource;
    this.operationName = operationName;
    setResourceName(resourceName, ResourceNamePriorities.DEFAULT);
    this.errorFlag = errorFlag;
    this.spanType = spanType;

    // Additional Metadata
    final Thread current = Thread.currentThread();
    this.threadId = current.getId();
    this.threadName = THREAD_NAMES.computeIfAbsent(current.getName(), Functions.UTF8_ENCODE);

    this.disableSamplingMechanismValidation = disableSamplingMechanismValidation;
    this.propagationTags =
        propagationTags != null
            ? propagationTags
            : traceCollector.getTracer().getPropagationTagsFactory().empty();
    this.propagationTags.updateTraceIdHighOrderBits(this.traceId.toHighOrderLong());
    this.injectBaggageAsTags = injectBaggageAsTags;
    this.injectLinksAsTags = injectLinksAsTags;
    if (origin != null) {
      setOrigin(origin);
    }
    if (samplingPriority != PrioritySampling.UNSET) {
      setSamplingPriority(samplingPriority, SamplingMechanism.UNKNOWN);
    }
    setTag(PARENT_ID, this.propagationTags.getLastParentId());
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }

  public long getParentId() {
    return parentId;
  }

  @Override
  public long getSpanId() {
    return spanId;
  }

  @Override
  public long getRootSpanId() {
    return getRootSpanContextOrThis().spanId;
  }

  @Override
  public long getTraceIdHigh() {
    return traceId.toHighOrderLong();
  }

  @Override
  public long getTraceIdLow() {
    return traceId.toLong();
  }

  @Override
  public int getEncodedOperationName() {
    return encodedOperationName;
  }

  @Override
  public int getEncodedResourceName() {
    return encodedResourceName;
  }

  public String getServiceName() {
    return serviceName;
  }

  private void internalSetServiceName(String serviceName) {
    this.serviceName = traceCollector.mapServiceName(serviceName);
    this.topLevel = isTopLevel(parentServiceName, this.serviceName);
  }

  public void setServiceName(final String serviceName) {
    setServiceName(serviceName, MANUAL);
  }

  public void setServiceName(String serviceName, @Nonnull CharSequence source) {
    internalSetServiceName(serviceName);
    setServiceNameSource(Objects.requireNonNull(source));
  }

  public CharSequence getServiceNameSource() {
    return serviceNameSource;
  }

  public void setServiceNameSource(final CharSequence serviceNameSource) {
    this.serviceNameSource = serviceNameSource;
  }

  // TODO this logic is inconsistent with hasResourceName
  public CharSequence getResourceName() {
    return isResourceNameSet() ? resourceName : operationName;
  }

  public boolean hasResourceName() {
    return isResourceNameSet() || getTag(DDTags.RESOURCE_NAME) != null;
  }

  public byte getResourceNamePriority() {
    return resourceNamePriority;
  }

  public void setResourceName(final CharSequence resourceName, byte priority) {
    if (log.isDebugEnabled()) {
      log.debug(
          "setResourceName `{}`->`{}` with priority {}->{} for traceId={} spanId={}",
          this.resourceName,
          resourceName,
          resourceNamePriority,
          priority,
          traceId,
          spanId);
    }
    if (null == resourceName) {
      return;
    }
    if (priority >= this.resourceNamePriority) {
      this.resourceNamePriority = priority;
      this.resourceName = resourceName;
      this.encodedResourceName = profilingContextIntegration.encodeResourceName(resourceName);
    }
  }

  private boolean isResourceNameSet() {
    return resourceName != null && resourceName.length() != 0;
  }

  public CharSequence getOperationName() {
    return operationName;
  }

  public void setOperationName(final CharSequence operationName) {
    this.operationName = operationName;
    this.encodedOperationName = profilingContextIntegration.encodeOperationName(operationName);
  }

  public boolean getErrorFlag() {
    return errorFlag;
  }

  public void setErrorFlag(final boolean errorFlag, final byte priority) {
    if (priority > UNSET && priority >= this.errorFlagPriority) {
      this.errorFlag = errorFlag;
      this.errorFlagPriority = priority;
    }
  }

  public boolean isMeasured() {
    return measured;
  }

  public void setMeasured(boolean measured) {
    if (measured != this.measured) {
      this.measured = measured;
    }
  }

  public boolean isTopLevel() {
    return topLevel;
  }

  private static boolean isTopLevel(String parentServiceName, String serviceName) {
    return parentServiceName == null
        || parentServiceName.length() == 0
        || !parentServiceName.equals(serviceName);
  }

  public CharSequence getSpanType() {
    return spanType;
  }

  public void setSpanType(final CharSequence spanType) {
    this.spanType = spanType;
  }

  /**
   * Applies a {@link SpanPrototype} as fallback defaults: stamps its span type, constant tags, and
   * integration name only where the span has not already set them. Prototype values are the lowest
   * precedence -- anything explicitly set (a builder {@code withSpanType}, explicit tags, an
   * earlier decorator) wins. Because it never clobbers, {@code apply} is order-independent and
   * self-neutralizes once construction has already seeded the same prototype.
   *
   * <p>This is the construction-time seam ({@code CoreSpanBuilder}) -- see {@link
   * #applyOverwriting} for the decorator {@code afterStart} seam (via {@link
   * DDSpan#applyOverwriting}), which needs different precedence. The context owns the tag map, so
   * the eventual cheaper bulk-share path (skipping interception for non-intercepted tags) and the
   * identity short-circuit will land here, with the dense store; a tag's intercept status is
   * already on its id ({@link KnownTagCodec#isIntercepted}). Until then each constant tag is
   * prechecked by the interceptor, identical to the per-tag calls this replaces.
   */
  public void apply(@Nonnull final SpanPrototype prototype) {
    if (this.spanType == null) {
      final CharSequence spanType = prototype.spanType();
      if (spanType != null) {
        setSpanType(spanType);
      }
    }
    seedAbsentTags(prototype.tags());
    if (this.integrationName == null) {
      final CharSequence integrationName = prototype.integrationName();
      if (integrationName != null) {
        setIntegrationName(integrationName);
      }
    }
  }

  /**
   * Applies a {@link SpanPrototype} unconditionally: stamps its span type, constant tags, and
   * integration name over whatever is already present. This is the decorator {@code afterStart}
   * seam -- see {@link datadog.trace.bootstrap.instrumentation.api.AgentSpan#applyOverwriting} for
   * why it needs different precedence than {@link #apply}.
   */
  public void applyOverwriting(@Nonnull final SpanPrototype prototype) {
    final CharSequence spanType = prototype.spanType();
    if (spanType != null) {
      setSpanType(spanType);
    }
    final TagMap tags = prototype.tags();
    setAllTags(tags, true);
    tagInterceptor.resolveSplitByTagsPrecedence(this, tags);
    final CharSequence integrationName = prototype.integrationName();
    if (integrationName != null) {
      setIntegrationName(integrationName);
    }
  }

  /**
   * Seeds tags that are not already present, routed through the interceptor. Mirrors {@link
   * #setAllTags(TagMap, boolean)}'s intercepting path but skips any key already set, so explicit
   * tags keep precedence over the prototype's constant defaults.
   */
  private void seedAbsentTags(final TagMap map) {
    if (map == null) {
      return;
    }
    synchronized (tagIds) {
      map.forEach(
          this,
          (ctx, tagEntry) -> {
            if (ctx.isPresent(tagEntry)) {
              return;
            }
            if (!ctx.tagInterceptor.interceptTag(this, tagEntry)) {
              ctx.putEntry(tagEntry);
            }
          });
    }
  }

  /** Forces the local root span sampling decision to keep according manual mechanism. */
  public void forceKeep() {
    forceKeep(SamplingMechanism.MANUAL);
  }

  public void forceKeep(byte samplingMechanism) {
    // set trace level sampling priority
    getRootSpanContextOrThis().forceKeepThisSpan(samplingMechanism);
  }

  private void forceKeepThisSpan(byte samplingMechanism) {
    propagationTags.forceKeep(samplingMechanism);
  }

  public void addPropagatedTraceSource(final int value) {
    propagationTags.addTraceSource(value);
  }

  public void updateDebugPropagation(String value) {
    propagationTags.updateDebugPropagation(value);
  }

  /**
   * @return if sampling priority was set by this method invocation
   */
  public boolean setSamplingPriority(final int newPriority, final int newMechanism) {
    DDSpanContext spanContext = getRootSpanContextOrThis();
    // set trace level sampling priority
    return spanContext.setThisSpanSamplingPriority(newPriority, newMechanism);
  }

  private DDSpanContext getRootSpanContextOrThis() {
    DDSpanContext rootSpanContext = getRootSpanContextIfDifferent();
    return rootSpanContext != null ? rootSpanContext : this;
  }

  private DDSpanContext getRootSpanContextIfDifferent() {
    if (traceCollector != null) {
      final DDSpan rootSpan = traceCollector.getRootSpan();
      if (null != rootSpan && rootSpan.spanContext() != this) {
        return rootSpan.spanContext();
      }
    }
    return null;
  }

  private boolean setThisSpanSamplingPriority(final int newPriority, final int newMechanism) {
    if (!validateSamplingPriority(newPriority, newMechanism)) {
      return false;
    }
    boolean updated =
        propagationTags.tryUpdateTraceSamplingPriority(
            newPriority,
            newMechanism,
            SamplingMechanism.canAvoidSamplingPriorityLock(newPriority, newMechanism));
    if (!updated) {
      if (log.isDebugEnabled()) {
        log.debug(
            "samplingPriority locked at priority: {}. Refusing to set to priority: {} mechanism: {}",
            propagationTags.getSamplingPriority(),
            newPriority,
            newMechanism);
      }
      return false;
    }
    return true;
  }

  public boolean setSamplingPriority(
      final int newPriority,
      final int newMechanism,
      final double sampleRate,
      final long traceIdLowOrderBits,
      final boolean rateLimiterRejected) {
    DDSpanContext spanContext = getRootSpanContextOrThis();
    if (!spanContext.validateSamplingPriority(newPriority, newMechanism)) {
      return false;
    }
    return spanContext.propagationTags.tryUpdateProbabilitySamplingDecision(
        newPriority,
        newMechanism,
        sampleRate,
        rateLimiterRejected,
        traceIdLowOrderBits,
        SamplingMechanism.canAvoidSamplingPriorityLock(newPriority, newMechanism));
  }

  private boolean validateSamplingPriority(final int newPriority, final int newMechanism) {
    if (newPriority == PrioritySampling.UNSET) {
      log.debug("{}: Refusing to set samplingPriority to UNSET", this);
      return false;
    }
    if (!SamplingMechanism.validateWithSamplingPriority(newMechanism, newPriority)) {
      if (disableSamplingMechanismValidation) {
        log.debug(
            "{}: Bypassing setting setSamplingPriority check ("
                + TracerConfig.SAMPLING_MECHANISM_VALIDATION_DISABLED
                + ") for a non valid combination of samplingMechanism {} and samplingPriority {}.",
            this,
            newMechanism,
            newPriority);
      } else {
        log.debug(
            "{}: Refusing to set samplingMechanism to {}. Provided samplingPriority {} is not allowed.",
            this,
            newMechanism,
            newPriority);
        return false;
      }
    }
    return true;
  }

  @Override
  public int getSamplingPriority() {
    return getRootSpanContextOrThis().propagationTags.getSamplingPriority();
  }

  public void setSpanSamplingPriority(double rate, int limit) {
    synchronized (tagIds) {
      unsafeSetTag(SPAN_SAMPLING_MECHANISM_TAG, SamplingMechanism.SPAN_SAMPLING_RATE);
      unsafeSetTag(SPAN_SAMPLING_RULE_RATE_TAG, rate);
      if (limit != Integer.MAX_VALUE) {
        unsafeSetTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG, limit);
      }
    }
  }

  /**
   * Prevent future changes to the context's sampling priority.
   *
   * <p>Used when a span is extracted or injected for propagation.
   *
   * <p>Has no effect if the sampling priority is unset.
   *
   * @return true if the sampling priority was locked.
   */
  @Deprecated
  public boolean lockSamplingPriority() {
    // this is now effectively a no-op - there is no locking.
    // the priority is just CAS'd against UNSET/UNKNOWN, unless it's forced to USER_KEEP/MANUAL
    // but is maintained for backwards compatibility, and returns false when it used to
    final DDSpan rootSpan = traceCollector.getRootSpan();
    if (null != rootSpan && rootSpan.spanContext() != this) {
      return rootSpan.spanContext().lockSamplingPriority();
    }

    return propagationTags.getSamplingPriority() != PrioritySampling.UNSET;
  }

  public CharSequence getOrigin() {
    return getRootSpanContextOrThis().origin;
  }

  public void beginEndToEnd() {
    traceCollector.beginEndToEnd();
  }

  public long getEndToEndStartTime() {
    return traceCollector.getEndToEndStartTime();
  }

  public void setBaggageItem(final String key, final String value) {
    if (key == null || value == null) {
      log.debug("Try to set invalid baggage: key = {}, value = {}", key, value);
      return;
    }
    if (baggageItems == EMPTY_BAGGAGE) {
      synchronized (this) {
        if (baggageItems == EMPTY_BAGGAGE) {
          baggageItems = new ConcurrentHashMap<>(4);
        }
      }
    }
    baggageItems.put(key, value);
  }

  public String getBaggageItem(final String key) {
    return baggageItems.get(key);
  }

  public Map<String, String> getBaggageItems() {
    return baggageItems;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return baggageItems.entrySet();
  }

  @Override
  public TraceCollector getTraceCollector() {
    return traceCollector;
  }

  public RequestContext getRequestContext() {
    return this;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return pathwayContext;
  }

  @Override
  public void mergePathwayContext(PathwayContext pathwayContext) {
    if (pathwayContext == null) {
      return;
    }

    // This is purposely not thread safe
    // The code randomly chooses between the two PathwayContexts.
    // If there is a race, then that's okay
    if (this.pathwayContext.isStarted()) {
      // Randomly select between keeping the current context (0) or replacing (1)
      if (ThreadLocalRandom.current().nextInt(2) == 1) {
        this.pathwayContext = pathwayContext;
      }
    } else {
      this.pathwayContext = pathwayContext;
    }
  }

  public CoreTracer getTracer() {
    return traceCollector.getTracer();
  }

  public void setHttpStatusCode(short statusCode) {
    this.httpStatusCode = statusCode;
  }

  public short getHttpStatusCode() {
    return httpStatusCode;
  }

  /** Identity-first string comparison: checks reference equality, then falls back to equals. */
  static boolean tagEquals(String tagValue, String tagLiteral) {
    return (tagValue == tagLiteral) || tagLiteral.equals(tagValue);
  }

  /**
   * Cache the span.kind ordinal for fast isOutbound() checks. Called from TagInterceptor when
   * span.kind is set.
   */
  public void setSpanKindOrdinal(String kind) {
    spanKindOrdinal = spanKindOrdinalOf(kind);
  }

  /**
   * This span's cached view of its tag stores, for its internal readers (post-processors, the
   * serializer, the snapshot copy) -- never its own set/get paths. Before each use it is pointed at
   * the current custom store, which the span creates lazily. Call under the {@code tagIds} lock.
   */
  private TagMap tagView() {
    TagMap view = this.tagView;
    if (view == null) {
      // a view handed to the post-processor chain escapes, so allocate it once per span
      this.tagView = view = TagMap.viewOf(tagIds, customTags, customTagsBorrowed);
    }
    view.attach(customTags, customTagsBorrowed);
    return view;
  }

  /**
   * Adopts the custom store a reader may have created through the view (by writing a custom tag),
   * so the span's own paths see it. Call under the {@code tagIds} lock, after using the view.
   */
  private void adoptViewStores(TagMap view) {
    this.customTags = view.customStore();
    this.customTagsBorrowed = view.isCustomStoreBorrowed();
  }

  /** The custom store to write to: created on the first custom write, ending any borrowing. */
  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "Written and read only under the tagIds lock")
  private HashStore customTagsForWrite() {
    HashStore custom = this.customTags;
    if (custom == null || this.customTagsBorrowed) {
      custom = TagRouting.newCustomStore(custom); // reads through to the store borrowed till now
      this.customTags = custom;
      this.customTagsBorrowed = false;
    }
    return custom;
  }

  /**
   * Removes the custom tag {@code name}: hiding an inherited one needs a store of the span's own.
   */
  private void removeCustomTag(String name) {
    HashStore custom = this.customTags;
    if (custom == null) {
      return;
    }
    if (this.customTagsBorrowed) {
      if (custom.get(name) == null) {
        return; // nothing to hide: keep borrowing
      }
      custom = customTagsForWrite();
    }
    custom.remove(name);
  }

  /** Removes {@code tag}, known or custom. Call under the {@code tagIds} lock. */
  private void removeStored(String tag) {
    long key = TagRouting.keyOf(tag);
    if (TagRouting.isKnown(key)) {
      tagIds.remove(key);
    } else {
      removeCustomTag(tag);
    }
  }

  /**
   * Copies {@code map}'s tags into the stores, sharing its Entry objects where a store keeps them.
   */
  private void putAllTags(TagMap map) {
    TagMap view = tagView();
    view.putAll(map);
    adoptViewStores(view);
  }

  /*
   * The resolved writes: a known tag by id, else a custom tag.
   */

  private void setResolved(long key, String tag, Object value) {
    if (TagRouting.isKnown(key)) {
      tagIds.set(key, value);
    } else {
      customTagsForWrite().set(tag, value);
    }
  }

  private void setResolved(long key, String tag, CharSequence value) {
    if (TagRouting.isKnown(key)) {
      tagIds.set(key, value);
    } else {
      customTagsForWrite().set(tag, value);
    }
  }

  private void setResolved(long key, String tag, boolean value) {
    if (TagRouting.isKnown(key)) {
      tagIds.set(key, value);
    } else {
      customTagsForWrite().set(tag, value);
    }
  }

  private void setResolved(long key, String tag, int value) {
    if (TagRouting.isKnown(key)) {
      tagIds.set(key, value);
    } else {
      customTagsForWrite().set(tag, value);
    }
  }

  private void setResolved(long key, String tag, long value) {
    if (TagRouting.isKnown(key)) {
      tagIds.set(key, value);
    } else {
      customTagsForWrite().set(tag, value);
    }
  }

  private void setResolved(long key, String tag, float value) {
    if (TagRouting.isKnown(key)) {
      tagIds.set(key, value);
    } else {
      customTagsForWrite().set(tag, value);
    }
  }

  private void setResolved(long key, String tag, double value) {
    if (TagRouting.isKnown(key)) {
      tagIds.set(key, value);
    } else {
      customTagsForWrite().set(tag, value);
    }
  }

  static byte spanKindOrdinalOf(String kind) {
    if (kind == null) {
      return SPAN_KIND_UNSET;
    } else if (tagEquals(kind, Tags.SPAN_KIND_SERVER)) {
      return SPAN_KIND_SERVER;
    } else if (tagEquals(kind, Tags.SPAN_KIND_CLIENT)) {
      return SPAN_KIND_CLIENT;
    } else if (tagEquals(kind, Tags.SPAN_KIND_PRODUCER)) {
      return SPAN_KIND_PRODUCER;
    } else if (tagEquals(kind, Tags.SPAN_KIND_CONSUMER)) {
      return SPAN_KIND_CONSUMER;
    } else if (tagEquals(kind, Tags.SPAN_KIND_INTERNAL)) {
      return SPAN_KIND_INTERNAL;
    } else if (tagEquals(kind, Tags.SPAN_KIND_BROKER)) {
      return SPAN_KIND_BROKER;
    } else {
      return SPAN_KIND_CUSTOM;
    }
  }

  byte getSpanKindOrdinal() {
    return spanKindOrdinal;
  }

  /** Returns the span.kind string from the cached ordinal, or falls back to the tag map. */
  public String getSpanKindString() {
    byte ordinal = spanKindOrdinal;
    if (ordinal > SPAN_KIND_UNSET && ordinal < SPAN_KIND_CUSTOM) {
      return SPAN_KIND_VALUES[ordinal];
    }
    // UNSET or CUSTOM -- fall through to tag map
    synchronized (tagIds) {
      TagMap.EntryReader kind = tagIds.getEntryReader(KnownTags.SPAN_KIND_ID);
      return kind == null ? null : kind.stringValue();
    }
  }

  public void setOrigin(final CharSequence origin) {
    DDSpanContext context = getRootSpanContextOrThis();
    context.origin = origin;
    context.propagationTags.updateTraceOrigin(origin);
  }

  public void setMetric(final CharSequence key, final Number value) {
    synchronized (tagIds) {
      unsafeSetTag(key.toString(), value);
    }
  }

  public void setMetric(final CharSequence key, final int value) {
    synchronized (tagIds) {
      storeTag(key.toString(), value);
    }
  }

  public void setMetric(final CharSequence key, final long value) {
    synchronized (tagIds) {
      storeTag(key.toString(), value);
    }
  }

  public void setMetric(final CharSequence key, final float value) {
    synchronized (tagIds) {
      storeTag(key.toString(), value);
    }
  }

  public void setMetric(final CharSequence key, final double value) {
    synchronized (tagIds) {
      storeTag(key.toString(), value);
    }
  }

  public void setMetric(final TagMap.EntryReader entry) {
    if (entry == null) {
      return;
    }

    synchronized (tagIds) {
      putEntry(entry);
    }
  }

  /*
   * Store a tag that interception let through, by name: resolved once to the store that holds it.
   * Call while holding the tags lock.
   */

  private void storeTag(String tag, Object value) {
    setResolved(TagRouting.keyOf(tag), tag, value);
  }

  private void storeTag(String tag, CharSequence value) {
    setResolved(TagRouting.keyOf(tag), tag, value);
  }

  private void storeTag(String tag, boolean value) {
    setResolved(TagRouting.keyOf(tag), tag, value);
  }

  private void storeTag(String tag, int value) {
    setResolved(TagRouting.keyOf(tag), tag, value);
  }

  private void storeTag(String tag, long value) {
    setResolved(TagRouting.keyOf(tag), tag, value);
  }

  private void storeTag(String tag, float value) {
    setResolved(TagRouting.keyOf(tag), tag, value);
  }

  private void storeTag(String tag, double value) {
    setResolved(TagRouting.keyOf(tag), tag, value);
  }

  /** Stores {@code entry}, sharing its Entry where the store keeps Entry objects. */
  private void putEntry(TagMap.EntryReader entry) {
    if (entry.tagId() != 0) {
      tagIds.put(entry); // no Entry built for a dense store, or for a flyweight from one
    } else {
      customTagsForWrite().put(entry.entry());
    }
  }

  public void removeTag(String tag) {
    if (tagEquals(tag, Tags.SPAN_KIND)) {
      // Clear the cached ordinal; the tag store still needs to be updated below.
      spanKindOrdinal = SPAN_KIND_UNSET;
    }
    synchronized (tagIds) {
      removeStored(tag);
    }
  }

  /**
   * Removes a known tag by id rather than by its name. An id that names no known tag is ignored,
   * like the id-keyed setters do.
   *
   * @param tagId a {@code KnownTags.*_ID} constant
   */
  public void removeTag(long tagId) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (tagId == KnownTags.SPAN_KIND_ID) {
      spanKindOrdinal = SPAN_KIND_UNSET;
    }
    synchronized (tagIds) {
      tagIds.remove(tagId);
    }
  }

  /**
   * Sets a tag to the span. Tags are not propagated to the children.
   *
   * <p>Existing tag value with the same value will be replaced. Setting a tag with a {@code null}
   * value will remove the tag from the span.
   *
   * <p>A known tag's name is resolved to its id once, and the id-keyed setter does the rest; only a
   * custom tag is handled by name.
   *
   * @param tag The tag name.
   * @param value The nullable tag value.
   */
  /** Whether {@code entry}'s tag is already on this span. */
  private boolean isPresent(TagMap.EntryReader entry) {
    String tag = entry.tag();
    return TagRouting.get(tagIds, customTags, TagRouting.keyOf(tag), tag) != null;
  }

  public void setTag(final String tag, final Object value) {
    if (null == tag) {
      return;
    }
    final long tagId = KnownTagCodec.keyOf(tag);
    if (tagId != 0) {
      setTag(tagId, value);
    } else if (null == value) {
      removeTag(tag);
    } else if (!tagInterceptor.interceptCustomTag(this, tag, value)) {
      synchronized (tagIds) {
        storeTag(tag, value);
      }
    }
  }

  public void setTag(final String tag, final String value) {
    if (null == tag) {
      return;
    }
    final long tagId = KnownTagCodec.keyOf(tag);
    if (tagId != 0) {
      setTag(tagId, value);
    } else if (null == value) {
      removeTag(tag);
    } else if (!tagInterceptor.interceptCustomTag(this, tag, value)) {
      synchronized (tagIds) {
        storeTag(tag, value);
      }
    }
  }

  /*
   * Id-keyed setters, mirroring the String setters above. With a constant id, the
   * KnownTagCodec.INTERCEPTED test folds, leaving only the split-by-tags table load; an id set never
   * takes the custom-tag path. An id that names no known tag is ignored, like a null tag.
   */
  public void setTag(final long tagId, final Object value) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (null == value) {
      removeTag(tagId);
      return;
    }
    if (!tagInterceptor.needsIntercept(tagId) || !tagInterceptor.interceptTag(this, tagId, value)) {
      synchronized (tagIds) {
        tagIds.set(tagId, value);
      }
    }
  }

  public void setTag(final long tagId, final CharSequence value) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (null == value) {
      removeTag(tagId);
      return;
    }
    if (!tagInterceptor.needsIntercept(tagId) || !tagInterceptor.interceptTag(this, tagId, value)) {
      synchronized (tagIds) {
        tagIds.set(tagId, value);
      }
    }
  }

  public void setTag(final long tagId, final boolean value) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (tagInterceptor.needsIntercept(tagId)) {
      this.setBox(tagId, value);
    } else {
      synchronized (tagIds) {
        tagIds.set(tagId, value);
      }
    }
  }

  public void setTag(final long tagId, final int value) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (tagInterceptor.needsIntercept(tagId)) {
      this.setBox(tagId, value);
    } else {
      synchronized (tagIds) {
        tagIds.set(tagId, value);
      }
    }
  }

  public void setTag(final long tagId, final long value) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (tagInterceptor.needsIntercept(tagId)) {
      this.setBox(tagId, value);
    } else {
      synchronized (tagIds) {
        tagIds.set(tagId, value);
      }
    }
  }

  public void setTag(final long tagId, final float value) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (tagInterceptor.needsIntercept(tagId)) {
      this.setBox(tagId, value);
    } else {
      synchronized (tagIds) {
        tagIds.set(tagId, value);
      }
    }
  }

  public void setTag(final long tagId, final double value) {
    if (!KnownTagCodec.isKnown(tagId)) {
      return;
    }
    if (tagInterceptor.needsIntercept(tagId)) {
      this.setBox(tagId, value);
    } else {
      synchronized (tagIds) {
        tagIds.set(tagId, value);
      }
    }
  }

  public void setTag(TagMap.EntryReader entry) {
    if (entry == null) {
      return;
    }

    // the interceptor prechecks the entry, to avoid boxing
    if (!tagInterceptor.interceptTag(this, entry)) {
      synchronized (tagIds) {
        putEntry(entry);
      }
    }
  }

  /*
   * Used when the interceptor's precheck says the tag may be intercepted, so boxing is unavoidable
   *
   * Either because the tagInterceptor needs to be fully checked (which requires boxing)
   * In that case, a box has already been created so it makes sense to pass the box
   * onto TagMap, since optimized TagMap will cache the box
   *
   * -- OR --
   *
   * The TagMap isn't optimized and will need to box the primitive regardless of
   * tag interception
   */
  private void setBox(long tagId, Object box) {
    if (!tagInterceptor.interceptTag(this, tagId, box)) {
      synchronized (tagIds) {
        tagIds.set(tagId, box);
      }
    }
  }

  public void setTag(final String tag, final boolean value) {
    if (null == tag) {
      return;
    }
    final long tagId = KnownTagCodec.keyOf(tag);
    if (tagId != 0) {
      setTag(tagId, value);
    } else if (!tagInterceptor.needsInterceptCustomTag(tag)
        || !tagInterceptor.interceptCustomTag(this, tag, value)) {
      synchronized (tagIds) {
        storeTag(tag, value);
      }
    }
  }

  public void setTag(final String tag, final int value) {
    if (null == tag) {
      return;
    }
    final long tagId = KnownTagCodec.keyOf(tag);
    if (tagId != 0) {
      setTag(tagId, value);
    } else if (!tagInterceptor.needsInterceptCustomTag(tag)
        || !tagInterceptor.interceptCustomTag(this, tag, value)) {
      synchronized (tagIds) {
        storeTag(tag, value);
      }
    }
  }

  public void setTag(final String tag, final long value) {
    if (null == tag) {
      return;
    }
    final long tagId = KnownTagCodec.keyOf(tag);
    if (tagId != 0) {
      setTag(tagId, value);
    } else if (!tagInterceptor.needsInterceptCustomTag(tag)
        || !tagInterceptor.interceptCustomTag(this, tag, value)) {
      synchronized (tagIds) {
        storeTag(tag, value);
      }
    }
  }

  public void setTag(final String tag, final float value) {
    if (null == tag) {
      return;
    }
    final long tagId = KnownTagCodec.keyOf(tag);
    if (tagId != 0) {
      setTag(tagId, value);
    } else if (!tagInterceptor.needsInterceptCustomTag(tag)
        || !tagInterceptor.interceptCustomTag(this, tag, value)) {
      synchronized (tagIds) {
        storeTag(tag, value);
      }
    }
  }

  public void setTag(final String tag, final double value) {
    if (null == tag) {
      return;
    }
    final long tagId = KnownTagCodec.keyOf(tag);
    if (tagId != 0) {
      setTag(tagId, value);
    } else if (!tagInterceptor.needsInterceptCustomTag(tag)
        || !tagInterceptor.interceptCustomTag(this, tag, value)) {
      synchronized (tagIds) {
        storeTag(tag, value);
      }
    }
  }

  void setAllTags(final TagMap map) {
    setAllTags(map, true);
  }

  void setAllTags(final TagMap map, boolean needsIntercept) {
    if (map == null) {
      return;
    }

    synchronized (tagIds) {
      if (needsIntercept) {
        // forEach out-performs the iterator of TagMap
        // Taking advantage of ability to pass through other context arguments
        // to avoid using a capturing lambda
        map.forEach(
            this,
            (ctx, tagEntry) -> {
              if (!ctx.tagInterceptor.interceptTag(this, tagEntry)) {
                ctx.putEntry(tagEntry);
              }
            });
      } else {
        putAllTags(map);
      }
    }
  }

  void setAllTags(final TagMap.Ledger ledger) {
    if (ledger == null) {
      return;
    }

    synchronized (tagIds) {
      for (final TagMap.EntryChange entryChange : ledger) {
        String tag = entryChange.tag();
        if (entryChange.isRemoval()) {
          if (tagEquals(tag, Tags.SPAN_KIND)) {
            // mirror removeTag(String): keep the cached ordinal in sync with the tags
            spanKindOrdinal = SPAN_KIND_UNSET;
          }
          removeStored(tag);
        } else {
          TagMap.Entry entry = (TagMap.Entry) entryChange;

          if (!tagInterceptor.interceptTag(this, entry)) {
            putEntry(entry);
          }
        }
      }
    }
  }

  void setAllTags(final Map<String, ?> map) {
    if (map == null) {
      return;
    } else if (map instanceof TagMap) {
      setAllTags((TagMap) map);
    } else if (!map.isEmpty()) {
      synchronized (tagIds) {
        for (final Map.Entry<String, ?> tag : map.entrySet()) {
          long tagId = KnownTagCodec.keyOf(tag.getKey());
          boolean intercepted =
              tagId != 0
                  ? tagInterceptor.interceptTag(this, tagId, tag.getValue())
                  : tagInterceptor.interceptCustomTag(this, tag.getKey(), tag.getValue());
          if (!intercepted) {
            unsafeSetTag(tag.getKey(), tag.getValue());
          }
        }
      }
    }
  }

  void unsafeSetTag(final String tag, final Object value) {
    storeTag(tag, value);
  }

  void unsafeSetTag(final String tag, final CharSequence value) {
    storeTag(tag, value);
  }

  void unsafeSetTag(final String tag, final byte value) {
    storeTag(tag, value);
  }

  void unsafeSetTag(final String tag, final double value) {
    storeTag(tag, value);
  }

  Object getTag(final String key) {
    switch (key) {
      case DDTags.THREAD_ID:
        return threadId;
      case DDTags.THREAD_NAME:
        // maintain previously observable type of the thread name :|
        return threadName.toString();
      case Tags.HTTP_STATUS:
      case KnownTags.HTTP_STATUS_CODE_OTEL_NAME:
        return 0 == httpStatusCode ? null : (int) httpStatusCode;
      case Tags.SPAN_KIND:
        return getSpanKindString();
      default:
        Object value;
        synchronized (tagIds) {
          value = unsafeGetTag(key);
        }
        // maintain previously observable type of http url :|
        return value == null ? null : Tags.HTTP_URL.equals(key) ? value.toString() : value;
    }
  }

  /**
   * This is not thread-safe and must only be used when it can be guaranteed that the context will
   * not be mutated. This is internal API and must not be exposed to users.
   *
   * @param tag
   * @return the value associated with the tag
   */
  public Object unsafeGetTag(final String tag) {
    long key = TagRouting.keyOf(tag);
    if (TagRouting.isKnown(key)) {
      return tagIds.getValue(key);
    }
    HashStore custom = customTags;
    TagMap.Entry entry = custom == null ? null : custom.get(tag);
    return entry == null ? null : entry.objectValue();
  }

  @Deprecated
  public TagMap getTags() {
    synchronized (tagIds) {
      TagMap tags = tagView().copy();

      tags.put(DDTags.THREAD_ID, threadId);
      // maintain previously observable type of the thread name :|
      tags.put(DDTags.THREAD_NAME, threadName.toString());
      int currentSamplingPriority = getSamplingPriority();
      // add _sample_rate tag only on the root/local span owning the decision
      if (getRootSpanContextIfDifferent() == null
          && currentSamplingPriority != PrioritySampling.UNSET) {
        tags.put(SAMPLE_RATE_KEY, currentSamplingPriority);
      }
      if (httpStatusCode != 0) {
        tags.put(Tags.HTTP_STATUS, (int) httpStatusCode);
      }
      // maintain previously observable type of http url :|
      Object value = tags.get(Tags.HTTP_URL);
      if (value != null) {
        tags.put(Tags.HTTP_URL, value.toString());
      }
      return tags.freeze();
    }
  }

  /**
   * @see CoreSpan#getMetaStruct()
   */
  public Map<String, Object> getMetaStruct() {
    return Collections.unmodifiableMap(metaStruct);
  }

  /**
   * @see CoreSpan#setMetaStruct(String, Object)
   */
  public <T> void setMetaStruct(final String field, final T value) {
    if (null == field) {
      return;
    }
    if (metaStruct == EMPTY_META_STRUCT) {
      synchronized (this) {
        if (metaStruct == EMPTY_META_STRUCT) {
          metaStruct = new ConcurrentHashMap<>(4);
        }
      }
    }
    if (null == value) {
      metaStruct.remove(field);
    } else {
      metaStruct.put(field, value);
    }
  }

  void earlyProcessTags(AppendableSpanLinks links) {
    synchronized (tagIds) {
      TagMap view = tagView();
      TagsPostProcessorFactory.eagerProcessor().processTags(view, this, links);
      adoptViewStores(view);
    }
  }

  /**
   * Processes the span tags and baggage, and hands the resulting {@link Metadata} to the consumer.
   *
   * @param consumer The consumer of the span metadata.
   * @param longRunningVersion The long-running version of the span.
   * @param restrictedSpan The span to read links and events from.
   * @param options The {@link CoreSpan} options describing what the serialization protocol needs.
   */
  void processTagsAndBaggage(
      final MetadataConsumer consumer, int longRunningVersion, DDSpan restrictedSpan, int options) {
    boolean injectLinksAsTags = this.injectLinksAsTags && (options & STRUCTURED_LINKS) == 0;
    boolean injectEventsAsTags = (options & STRUCTURED_EVENTS) == 0;
    PropagationTags serializedPropagationTags;
    if ((options & ROOT_PROPAGATION_TAGS) != 0) {
      serializedPropagationTags = getPropagationTags();
    } else if ((options & OWN_PROPAGATION_TAGS) != 0) {
      serializedPropagationTags = this.propagationTags;
    } else {
      serializedPropagationTags = null;
    }
    // NOTE: The span is passed for the sole purpose of allowing updating & reading of the span
    // links and events
    // This is a compromise to avoid...
    // - creating an extra wrapper object that would create significant allocation
    // - implementing an interface to read the spans that require making the read method public
    synchronized (tagIds) {
      // One live view of the tag stores, for the post-processors and the serializer
      TagMap tags = tagView();

      // Tags
      TagsPostProcessorFactory.lazyProcessor().processTags(tags, this, restrictedSpan);

      // Links
      if (injectLinksAsTags) {
        String linksTag = DDSpanLink.toTag(restrictedSpan.getLinks());
        if (linksTag != null) {
          tags.set(SPAN_LINKS, linksTag);
        }
      }

      // Events
      List<DDSpanEvent> events = restrictedSpan.getEvents();
      if (injectEventsAsTags) {
        String eventsTag = spanEventsToTag(events);
        if (eventsTag != null) {
          tags.set(SPAN_EVENTS, eventsTag);
        }
      }

      // Baggage
      Map<String, String> baggageItemsWithPropagationTags;
      if (this.injectBaggageAsTags) {
        baggageItemsWithPropagationTags = new HashMap<>(baggageItems);
        if (w3cBaggage != null) {
          injectW3CBaggageTags(baggageItemsWithPropagationTags);
        }
        if (serializedPropagationTags != null) {
          serializedPropagationTags.fillTagMap(baggageItemsWithPropagationTags);
        }
      } else {
        baggageItemsWithPropagationTags =
            serializedPropagationTags == null
                ? EMPTY_BAGGAGE
                : serializedPropagationTags.createTagMap();
      }

      consumer.accept(
          new Metadata(
              threadId,
              threadName,
              tags,
              baggageItemsWithPropagationTags,
              getSamplingPriority(),
              measured,
              topLevel,
              httpStatusCode,
              // Get origin from rootSpan.context
              getOrigin(),
              longRunningVersion,
              ProcessTags.getTagsForSerialization(),
              Config.get().isOtlpTracesExportEnabled() ? OTLP_EXPORT_TRUE : OTLP_EXPORT_FALSE,
              restrictedSpan.getLinks(),
              events));
      adoptViewStores(tags);
    }
  }

  /**
   * Encodes span events into a {@link DDTags#SPAN_EVENTS} tag value.
   *
   * @param events The span events to encode.
   * @return The encoded tag value, {@code null} if no events.
   */
  private static String spanEventsToTag(List<DDSpanEvent> events) {
    if (events.isEmpty()) {
      return null;
    }
    StringBuilder builder = new StringBuilder("[");
    for (DDSpanEvent event : events) {
      if (builder.length() > 1) {
        builder.append(',');
      }
      builder.append("{\"time_unix_nano\":").append(event.timeUnixNano()).append(",\"name\":");
      appendJsonString(builder, event.name());
      Map<String, ?> attributes = event.attributes();
      if (!attributes.isEmpty()) {
        builder.append(",\"attributes\":{");
        int attributesStart = builder.length();
        for (Map.Entry<String, ?> attribute : attributes.entrySet()) {
          if (builder.length() > attributesStart) {
            builder.append(',');
          }
          appendJsonString(builder, attribute.getKey());
          builder.append(':');
          appendJsonValue(builder, attribute.getValue());
        }
        builder.append('}');
      }
      builder.append('}');
    }
    return builder.append(']').toString();
  }

  private static void appendJsonValue(StringBuilder builder, Object value) {
    if (value instanceof String) {
      appendJsonString(builder, (String) value);
    } else if (value instanceof List) {
      List<?> values = (List<?>) value;
      builder.append('[');
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          builder.append(',');
        }
        appendJsonValue(builder, values.get(i));
      }
      builder.append(']');
    } else if (value instanceof Number || value instanceof Boolean) {
      builder.append(value);
    } else {
      builder.append("null"); // null for unsupported types
    }
  }

  private static void appendJsonString(StringBuilder builder, String value) {
    if (value == null) {
      builder.append("null");
      return;
    }
    builder.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\':
          builder.append("\\\\");
          break;
        case '"':
          builder.append("\\\"");
          break;
        case '\b':
          builder.append("\\b");
          break;
        case '\f':
          builder.append("\\f");
          break;
        case '\n':
          builder.append("\\n");
          break;
        case '\r':
          builder.append("\\r");
          break;
        case '\t':
          builder.append("\\t");
          break;
        default:
          builder.append(c);
      }
    }
    builder.append('"');
  }

  void injectW3CBaggageTags(Map<String, String> baggageItemsWithPropagationTags) {
    List<String> baggageTagKeys = Config.get().getTraceBaggageTagKeys();
    if (baggageTagKeys.isEmpty()) {
      return;
    }
    Map<String, String> w3cBaggageItems = w3cBaggage.asMap();
    for (String key : baggageTagKeys) {
      if ("*".equals(key)) {
        // If the key is "*", we add all baggage items
        for (Map.Entry<String, String> entry : w3cBaggageItems.entrySet()) {
          baggageItemsWithPropagationTags.put("baggage." + entry.getKey(), entry.getValue());
        }
        break;
      }
      String value = w3cBaggageItems.get(key);
      if (value != null) {
        baggageItemsWithPropagationTags.put("baggage." + key, value);
      }
    }
  }

  @Override
  public void setIntegrationName(CharSequence integrationName) {
    this.integrationName = integrationName;
  }

  @Override
  public CharSequence getIntegrationName() {
    return integrationName;
  }

  @Override
  public String toString() {
    final StringBuilder s =
        new StringBuilder()
            .append("DDSpan [ t_id=")
            .append(traceId)
            .append(", s_id=")
            .append(DDSpanId.toString(spanId))
            .append(", p_id=")
            .append(DDSpanId.toString(parentId))
            .append(" ] trace=")
            .append(getServiceName())
            .append('/')
            .append(getOperationName())
            .append('/')
            .append(getResourceName());
    if (errorFlag) {
      s.append(" *errored*");
    }
    if (measured) {
      s.append(" *measured*");
    }

    synchronized (tagIds) {
      s.append(" tags=").append(new TreeMap<>(getTags()));
    }
    return s.toString();
  }

  /** RequestContext Implementation */
  @Override
  public Object getData(RequestContextSlot slot) {
    if (slot == RequestContextSlot.APPSEC) {
      return this.requestContextDataAppSec;
    } else if (slot == RequestContextSlot.CI_VISIBILITY) {
      return this.ciVisibilityContextData;
    } else if (slot == RequestContextSlot.IAST) {
      return this.requestContextDataIast;
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    Exception exc = null;
    if (this.requestContextDataAppSec instanceof Closeable) {
      try {
        ((Closeable) this.requestContextDataAppSec).close();
      } catch (IOException | RuntimeException e) {
        exc = e;
      }
    }
    if (this.requestContextDataIast instanceof Closeable) {
      try {
        ((Closeable) this.requestContextDataIast).close();
      } catch (IOException | RuntimeException e) {
        exc = e;
      }
    }
    if (exc != null) {
      if (exc instanceof RuntimeException) {
        throw (RuntimeException) exc;
      } else {
        throw (IOException) exc;
      }
    }
  }

  @Override
  public TraceSegment getTraceSegment() {
    return this;
  }

  @Override
  public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {
    getRootSpanContextOrThis().blockResponseFunction = blockResponseFunction;
  }

  @Override
  public BlockResponseFunction getBlockResponseFunction() {
    return getRootSpanContextOrThis().blockResponseFunction;
  }

  @Override
  public void setClientIpAddressData(final ClientIpAddressData clientIpAddressData) {
    getRootSpanContextOrThis().clientIpAddressData = clientIpAddressData;
  }

  @Override
  public ClientIpAddressData getClientIpAddressData() {
    return getRootSpanContextOrThis().clientIpAddressData;
  }

  public PropagationTags getPropagationTags() {
    return getRootSpanContextOrThis().propagationTags;
  }

  @Override
  public LLMObsPropagationValues getExtractedLLMObsValues() {
    return getPropagationTags().getExtractedLLMObsValues();
  }

  /** TraceSegment Implementation */
  @Override
  public void setTagTop(String key, Object value, boolean sanitize) {
    getRootSpanContextOrThis().setTagCurrent(key, value, sanitize);
  }

  @Override
  public Object getTagTop(String key, boolean sanitize) {
    return getRootSpanContextOrThis().getTagCurrent(key, sanitize);
  }

  @Override
  public void setTagCurrent(String key, Object value, boolean sanitize) {
    if (sanitize) {
      key = TagsHelper.sanitize(key);
    }
    this.setTag(key, value);
  }

  @Override
  public Object getTagCurrent(String key, boolean sanitize) {
    if (sanitize) {
      key = TagsHelper.sanitize(key);
    }
    return this.getTag(key);
  }

  @Override
  public void setDataTop(String key, Object value) {
    getRootSpanContextOrThis().setDataCurrent(key, value);
  }

  @Override
  public Object getDataTop(String key) {
    return getRootSpanContextOrThis().getDataCurrent(key);
  }

  @Override
  public void effectivelyBlocked() {
    setTagTop("appsec.blocked", "true");
  }

  @Override
  public void setDataCurrent(String key, Object value) {
    this.setTag(getTagName(key), value);
  }

  @Override
  public Object getDataCurrent(String key) {
    return this.getTag(getTagName(key));
  }

  private String getTagName(String key) {
    // TODO is this decided?
    return "_dd." + key + ".json";
  }

  @Override
  public void setMetaStructTop(String field, Object value) {
    getRootSpanContextOrThis().setMetaStructCurrent(field, value);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getOrCreateMetaStructTop(final String key, final Function<String, T> defaultValue) {
    final DDSpanContext top = getRootSpanContextOrThis();
    if (top.metaStruct == EMPTY_META_STRUCT) {
      // this will safely create the metastruct if the field has not been initialized already
      top.setMetaStruct(key, defaultValue.apply(key));
    }
    return (T) top.metaStruct.computeIfAbsent(key, defaultValue);
  }

  @Override
  public void setMetaStructCurrent(String field, Object value) {
    setMetaStruct(field, value);
  }

  @Override
  public boolean isRemote() {
    return false;
  }
}
