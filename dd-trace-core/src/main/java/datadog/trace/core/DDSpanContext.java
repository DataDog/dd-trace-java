package datadog.trace.core;

import static datadog.trace.api.cache.RadixTreeCache.HTTP_STATUSES;
import static datadog.trace.bootstrap.instrumentation.api.ErrorPriorities.UNSET;

import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.taginterceptor.TagInterceptor;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.util.TagsHelper;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
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
    implements AgentSpan.Context, RequestContext, TraceSegment, ProfilerContext {
  private static final Logger log = LoggerFactory.getLogger(DDSpanContext.class);

  public static final String PRIORITY_SAMPLING_KEY = "_sampling_priority_v1";
  public static final String SAMPLE_RATE_KEY = "_sample_rate";

  public static final String SPAN_SAMPLING_MECHANISM_TAG = "_dd.span_sampling.mechanism";
  public static final String SPAN_SAMPLING_RULE_RATE_TAG = "_dd.span_sampling.rule_rate";
  public static final String SPAN_SAMPLING_MAX_PER_SECOND_TAG = "_dd.span_sampling.max_per_second";

  private static final DDCache<String, UTF8BytesString> THREAD_NAMES =
      DDCaches.newFixedSizeCache(256);

  private static final Map<String, String> EMPTY_BAGGAGE = Collections.emptyMap();

  /** The collection of all span related to this one */
  private final PendingTrace trace;

  /** Baggage is associated with the whole trace and shared with other spans */
  private volatile Map<String, String> baggageItems;

  // Not Shared with other span contexts
  private final DDTraceId traceId;
  private final long spanId;
  private final long parentId;

  private final String parentServiceName;

  private final long threadId;
  private final UTF8BytesString threadName;

  private volatile short httpStatusCode;

  /**
   * Tags are associated to the current span, they will not propagate to the children span.
   *
   * <p>The underlying assumption for using a normal Map with synchronized access instead of a
   * ConcurrentHashMap is that even though the tags can be accessed and modified from multiple
   * threads, they will rarely, if ever, be read and modified concurrently by multiple threads but
   * rather read and accessed in a serial fashion on thread after thread. The synchronization can
   * then be wrapped around bulk operations to minimize the costly atomic operations.
   */
  private final Map<String, Object> unsafeTags;

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

  private static final AtomicIntegerFieldUpdater<DDSpanContext> SAMPLING_PRIORITY_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(DDSpanContext.class, "samplingPriority");

  private volatile int samplingPriority = PrioritySampling.UNSET;

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

  private final ProfilingContextIntegration profilingContextIntegration;
  private boolean injectBaggageAsTags;
  private volatile int encodedOperationName;

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
      final PendingTrace trace,
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
        serviceName,
        operationName,
        resourceName,
        samplingPriority,
        origin,
        baggageItems,
        errorFlag,
        spanType,
        tagsSize,
        trace,
        requestContextDataAppSec,
        requestContextDataIast,
        null,
        pathwayContext,
        disableSamplingMechanismValidation,
        propagationTags,
        ProfilingContextIntegration.NoOp.INSTANCE,
        true);
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
      final PendingTrace trace,
      final Object requestContextDataAppSec,
      final Object requestContextDataIast,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation,
      final PropagationTags propagationTags,
      final boolean injectBaggageAsTags) {
    this(
        traceId,
        spanId,
        parentId,
        parentServiceName,
        serviceName,
        operationName,
        resourceName,
        samplingPriority,
        origin,
        baggageItems,
        errorFlag,
        spanType,
        tagsSize,
        trace,
        requestContextDataAppSec,
        requestContextDataIast,
        null,
        pathwayContext,
        disableSamplingMechanismValidation,
        propagationTags,
        ProfilingContextIntegration.NoOp.INSTANCE,
        injectBaggageAsTags);
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
      final PendingTrace trace,
      final Object requestContextDataAppSec,
      final Object requestContextDataIast,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation,
      final PropagationTags propagationTags,
      final ProfilingContextIntegration profilingContextIntegration) {
    this(
        traceId,
        spanId,
        parentId,
        parentServiceName,
        serviceName,
        operationName,
        resourceName,
        samplingPriority,
        origin,
        baggageItems,
        errorFlag,
        spanType,
        tagsSize,
        trace,
        requestContextDataAppSec,
        requestContextDataIast,
        null,
        pathwayContext,
        disableSamplingMechanismValidation,
        propagationTags,
        profilingContextIntegration,
        true);
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
      final PendingTrace trace,
      final Object requestContextDataAppSec,
      final Object requestContextDataIast,
      final Object CiVisibilityContextData,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation,
      final PropagationTags propagationTags,
      final ProfilingContextIntegration profilingContextIntegration,
      final boolean injectBaggageAsTags) {

    assert trace != null;
    this.trace = trace;

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

    this.requestContextDataAppSec = requestContextDataAppSec;
    this.requestContextDataIast = requestContextDataIast;
    this.ciVisibilityContextData = CiVisibilityContextData;

    assert pathwayContext != null;
    this.pathwayContext = pathwayContext;

    // The +1 is the magic number from the tags below that we set at the end,
    // and "* 4 / 3" is to make sure that we don't resize immediately
    final int capacity = Math.max((tagsSize <= 0 ? 3 : (tagsSize + 1)) * 4 / 3, 8);
    this.unsafeTags = new HashMap<>(capacity);
    // must set this before setting the service and resource names below
    this.profilingContextIntegration = profilingContextIntegration;
    // as fast as we can try to make this operation, we still might need to activate/deactivate
    // contexts at alarming rates in unpredictable async applications, so we'll try
    // to get away with doing this just once per span
    this.encodedOperationName = profilingContextIntegration.encode(operationName);

    setServiceName(serviceName);
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
            : trace.getTracer().getPropagationTagsFactory().empty();
    this.propagationTags.updateTraceIdHighOrderBits(this.traceId.toHighOrderLong());
    this.injectBaggageAsTags = injectBaggageAsTags;
    if (origin != null) {
      setOrigin(origin);
    }
    if (samplingPriority != PrioritySampling.UNSET) {
      setSamplingPriority(samplingPriority, SamplingMechanism.UNKNOWN);
    }
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
  public int getEncodedOperationName() {
    return encodedOperationName;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(final String serviceName) {
    this.serviceName = trace.mapServiceName(serviceName);
    this.topLevel = isTopLevel(parentServiceName, this.serviceName);
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
    if (null == resourceName) {
      return;
    }
    if (priority >= this.resourceNamePriority) {
      this.resourceNamePriority = priority;
      this.resourceName = resourceName;
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
    this.encodedOperationName = profilingContextIntegration.encode(operationName);
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

  public void forceKeep() {
    // set trace level sampling priority
    getRootSpanContextOrThis().forceKeepThisSpan(SamplingMechanism.MANUAL);
  }

  private void forceKeepThisSpan(byte samplingMechanism) {
    // if the user really wants to keep this trace chunk, we will let them,
    // even if the old sampling priority and mechanism have already propagated
    if (SAMPLING_PRIORITY_UPDATER.getAndSet(this, PrioritySampling.USER_KEEP)
        == PrioritySampling.UNSET) {
      propagationTags.updateTraceSamplingPriority(PrioritySampling.USER_KEEP, samplingMechanism);
    }
  }

  /** @return if sampling priority was set by this method invocation */
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
    if (trace != null) {
      final DDSpan rootSpan = trace.getRootSpan();
      if (null != rootSpan && rootSpan.context() != this) {
        return rootSpan.context();
      }
    }
    return null;
  }

  private boolean setThisSpanSamplingPriority(final int newPriority, final int newMechanism) {
    if (!validateSamplingPriority(newPriority, newMechanism)) {
      return false;
    }
    if (!SAMPLING_PRIORITY_UPDATER.compareAndSet(this, PrioritySampling.UNSET, newPriority)) {
      if (log.isDebugEnabled()) {
        log.debug(
            "samplingPriority locked at priority: {}. Refusing to set to priority: {} mechanism: {}",
            samplingPriority,
            newPriority,
            newMechanism);
      }
      return false;
    }
    // set trace level sampling priority tag propagationTags
    propagationTags.updateTraceSamplingPriority(newPriority, newMechanism);
    return true;
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
    return getRootSpanContextOrThis().samplingPriority;
  }

  public void setSpanSamplingPriority(double rate, int limit) {
    synchronized (unsafeTags) {
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
    final DDSpan rootSpan = trace.getRootSpan();
    if (null != rootSpan && rootSpan.context() != this) {
      return rootSpan.context().lockSamplingPriority();
    }

    return SAMPLING_PRIORITY_UPDATER.get(this) != PrioritySampling.UNSET;
  }

  public CharSequence getOrigin() {
    return getRootSpanContextOrThis().origin;
  }

  public void beginEndToEnd() {
    trace.beginEndToEnd();
  }

  public long getEndToEndStartTime() {
    return trace.getEndToEndStartTime();
  }

  public void setBaggageItem(final String key, final String value) {
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
  public PendingTrace getTrace() {
    return trace;
  }

  public RequestContext getRequestContext() {
    return this;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return pathwayContext;
  }

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
    return trace.getTracer();
  }

  public void setHttpStatusCode(short statusCode) {
    this.httpStatusCode = statusCode;
  }

  public short getHttpStatusCode() {
    return httpStatusCode;
  }

  public void setOrigin(final CharSequence origin) {
    DDSpanContext context = getRootSpanContextOrThis();
    context.origin = origin;
    context.propagationTags.updateTraceOrigin(origin);
  }

  public void setMetric(final CharSequence key, final Number value) {
    synchronized (unsafeTags) {
      unsafeSetTag(key.toString(), value);
    }
  }

  /**
   * Sets a tag to the span. Tags are not propagated to the children.
   *
   * <p>Existing tag value with the same value will be replaced. Setting a tag with a {@code null}
   * value will remove the tag from the span.
   *
   * @param tag The tag name.
   * @param value The nullable tag value.
   */
  public void setTag(final String tag, final Object value) {
    if (null == tag) {
      return;
    }
    if (null == value) {
      synchronized (unsafeTags) {
        unsafeTags.remove(tag);
      }
    } else if (!trace.getTracer().getTagInterceptor().interceptTag(this, tag, value)) {
      synchronized (unsafeTags) {
        unsafeSetTag(tag, value);
      }
    }
  }

  void setAllTags(final Map<String, ?> map) {
    if (map == null || map.isEmpty()) {
      return;
    }

    TagInterceptor tagInterceptor = trace.getTracer().getTagInterceptor();
    synchronized (unsafeTags) {
      for (final Map.Entry<String, ?> tag : map.entrySet()) {
        if (!tagInterceptor.interceptTag(this, tag.getKey(), tag.getValue())) {
          unsafeSetTag(tag.getKey(), tag.getValue());
        }
      }
    }
  }

  void unsafeSetTag(final String tag, final Object value) {
    unsafeTags.put(tag, value);
  }

  Object getTag(final String key) {
    switch (key) {
      case DDTags.THREAD_ID:
        return threadId;
      case DDTags.THREAD_NAME:
        // maintain previously observable type of the thread name :|
        return threadName.toString();
      case Tags.HTTP_STATUS:
        return 0 == httpStatusCode ? null : (int) httpStatusCode;
      default:
        Object value;
        synchronized (unsafeTags) {
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
    return unsafeTags.get(tag);
  }

  public Map<String, Object> getTags() {
    synchronized (unsafeTags) {
      Map<String, Object> tags = new HashMap<>(unsafeTags);
      tags.put(DDTags.THREAD_ID, threadId);
      // maintain previously observable type of the thread name :|
      tags.put(DDTags.THREAD_NAME, threadName.toString());
      if (samplingPriority != PrioritySampling.UNSET) {
        tags.put(SAMPLE_RATE_KEY, samplingPriority);
      }
      if (httpStatusCode != 0) {
        tags.put(Tags.HTTP_STATUS, (int) httpStatusCode);
      }
      // maintain previously observable type of http url :|
      Object value = tags.get(Tags.HTTP_URL);
      if (value != null) {
        tags.put(Tags.HTTP_URL, value.toString());
      }
      return Collections.unmodifiableMap(tags);
    }
  }

  public void processTagsAndBaggage(final MetadataConsumer consumer, int longRunningVersion) {
    synchronized (unsafeTags) {
      Map<String, String> baggageItemsWithPropagationTags;
      if (injectBaggageAsTags) {
        baggageItemsWithPropagationTags = new HashMap<>(baggageItems);
        propagationTags.fillTagMap(baggageItemsWithPropagationTags);
      } else {
        baggageItemsWithPropagationTags = propagationTags.createTagMap();
      }
      consumer.accept(
          new Metadata(
              threadId,
              threadName,
              TagsPostProcessorFactory.instance().processTagsWithContext(unsafeTags, this),
              baggageItemsWithPropagationTags,
              samplingPriority != PrioritySampling.UNSET ? samplingPriority : getSamplingPriority(),
              measured,
              topLevel,
              httpStatusCode == 0 ? null : HTTP_STATUSES.get(httpStatusCode),
              // Get origin from rootSpan.context
              getOrigin(),
              longRunningVersion));
    }
  }

  @Override
  public String toString() {
    final StringBuilder s =
        new StringBuilder()
            .append("DDSpan [ t_id=")
            .append(traceId)
            .append(", s_id=")
            .append(spanId)
            .append(", p_id=")
            .append(parentId)
            .append(" ] trace=")
            .append(getServiceName())
            .append("/")
            .append(getOperationName())
            .append("/")
            .append(getResourceName());
    if (errorFlag) {
      s.append(" *errored*");
    }
    if (measured) {
      s.append(" *measured*");
    }

    synchronized (unsafeTags) {
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

  public PropagationTags getPropagationTags() {
    return getRootSpanContextOrThis().propagationTags;
  }

  /** TraceSegment Implementation */
  @Override
  public void setTagTop(String key, Object value, boolean sanitize) {
    getRootSpanContextOrThis().setTagCurrent(key, value, sanitize);
  }

  @Override
  public void setTagCurrent(String key, Object value, boolean sanitize) {
    if (sanitize) {
      key = TagsHelper.sanitize(key);
    }
    this.setTag(key, value);
  }

  @Override
  public void setDataTop(String key, Object value) {
    getRootSpanContextOrThis().setDataCurrent(key, value);
  }

  @Override
  public void effectivelyBlocked() {
    setTag("appsec.blocked", "true");
  }

  @Override
  public void setDataCurrent(String key, Object value) {
    // TODO is this decided?
    String tagKey = "_dd." + key + ".json";
    this.setTag(tagKey, value);
  }
}
