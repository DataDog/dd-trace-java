package datadog.trace.core;

import static datadog.trace.api.cache.RadixTreeCache.HTTP_STATUSES;

import datadog.trace.api.DDId;
import datadog.trace.api.DDTags;
import datadog.trace.api.Functions;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.taginterceptor.TagInterceptor;
import datadog.trace.core.tagprocessor.QueryObfuscator;
import datadog.trace.core.tagprocessor.TagsPostProcessor;
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
public class DDSpanContext implements AgentSpan.Context, RequestContext<Object>, TraceSegment {
  private static final Logger log = LoggerFactory.getLogger(DDSpanContext.class);

  public static final String PRIORITY_SAMPLING_KEY = "_sampling_priority_v1";
  public static final String SAMPLE_RATE_KEY = "_sample_rate";

  private static final DDCache<String, UTF8BytesString> THREAD_NAMES =
      DDCaches.newFixedSizeCache(256);

  private static final Map<String, String> EMPTY_BAGGAGE = Collections.emptyMap();

  /** The collection of all span related to this one */
  private final PendingTrace trace;

  /** Baggage is associated with the whole trace and shared with other spans */
  private volatile Map<String, String> baggageItems;

  // Not Shared with other span contexts
  private final DDId traceId;
  private final DDId spanId;
  private final DDId parentId;

  private final String parentServiceName;

  private final long threadId;
  private final UTF8BytesString threadName;

  private volatile short httpStatusCode;

  private static final TagsPostProcessor postProcessor = new QueryObfuscator();

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

  private volatile boolean measured;

  private volatile boolean topLevel;

  private static final AtomicIntegerFieldUpdater<DDSpanContext> SAMPLING_DECISION_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(DDSpanContext.class, "samplingDecision");

  private volatile int samplingDecision = SamplingDecision.UNSET_UNKNOWN;

  /** The origin of the trace. (eg. Synthetics, CI App) */
  private volatile CharSequence origin;

  /** RequestContext data for the InstrumentationGateway */
  private final Object requestContextData;

  private final boolean disableSamplingMechanismValidation;

  private volatile PathwayContext pathwayContext;

  /** Aims to pack sampling priority and sampling mechanism into one value */
  protected static class SamplingDecision {

    public static final int UNSET_UNKNOWN =
        create(PrioritySampling.UNSET, SamplingMechanism.UNKNOWN);

    public static int create(int priority, int mechanism) {
      return priority << 16 | (byte) mechanism & 0xFFFF;
    }

    public static int priority(int samplingDecision) {
      return samplingDecision >> 16;
    }

    public static int mechanism(int samplingDecision) {
      return (byte) samplingDecision;
    }

    private SamplingDecision() {}
  }

  public DDSpanContext(
      final DDId traceId,
      final DDId spanId,
      final DDId parentId,
      final CharSequence parentServiceName,
      final String serviceName,
      final CharSequence operationName,
      final CharSequence resourceName,
      final int samplingPriority,
      final int samplingMechanism,
      final CharSequence origin,
      final Map<String, String> baggageItems,
      final boolean errorFlag,
      final CharSequence spanType,
      final int tagsSize,
      final PendingTrace trace,
      final Object requestContextData,
      final PathwayContext pathwayContext,
      final boolean disableSamplingMechanismValidation) {

    assert trace != null;
    this.trace = trace;

    assert traceId != null;
    assert spanId != null;
    assert parentId != null;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;
    this.parentServiceName = String.valueOf(parentServiceName);

    if (baggageItems == null || baggageItems.isEmpty()) {
      this.baggageItems = EMPTY_BAGGAGE;
    } else {
      this.baggageItems = new ConcurrentHashMap<>(baggageItems);
    }

    this.requestContextData = requestContextData;

    assert pathwayContext != null;
    this.pathwayContext = pathwayContext;

    // The +1 is the magic number from the tags below that we set at the end,
    // and "* 4 / 3" is to make sure that we don't resize immediately
    final int capacity = Math.max((tagsSize <= 0 ? 3 : (tagsSize + 1)) * 4 / 3, 8);
    this.unsafeTags = new HashMap<>(capacity);

    setServiceName(serviceName);
    this.operationName = operationName;
    this.resourceName = resourceName;
    this.errorFlag = errorFlag;
    this.spanType = spanType;
    this.origin = origin;

    long samplingParams = SamplingDecision.create(samplingPriority, samplingMechanism);
    if (samplingParams != SamplingDecision.UNSET_UNKNOWN) {
      setSamplingPriority(samplingPriority, samplingMechanism);
    }

    // Additional Metadata
    final Thread current = Thread.currentThread();
    this.threadId = current.getId();
    this.threadName = THREAD_NAMES.computeIfAbsent(current.getName(), Functions.UTF8_ENCODE);

    this.disableSamplingMechanismValidation = disableSamplingMechanismValidation;
  }

  @Override
  public DDId getTraceId() {
    return traceId;
  }

  public DDId getParentId() {
    return parentId;
  }

  @Override
  public DDId getSpanId() {
    return spanId;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(final String serviceName) {
    this.serviceName = trace.getTracer().mapServiceName(serviceName);
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
  }

  public boolean getErrorFlag() {
    return errorFlag;
  }

  public void setErrorFlag(final boolean errorFlag) {
    if (errorFlag != this.errorFlag) {
      this.errorFlag = errorFlag;
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
    if (trace != null) {
      final DDSpan rootSpan = trace.getRootSpan();
      if (null != rootSpan && rootSpan.context() != this) {
        rootSpan.context().forceKeep();
        return;
      }
    }
    // if the user really wants to keep this trace chunk, we will let them,
    // even if the old sampling priority and mechanism have already propagated
    int newSamplingPriorityAndMechanism =
        SamplingDecision.create(PrioritySampling.USER_KEEP, SamplingMechanism.MANUAL);
    SAMPLING_DECISION_UPDATER.set(this, newSamplingPriorityAndMechanism);
  }

  /** @return if sampling priority was set by this method invocation */
  public boolean setSamplingPriority(final int newPriority, final int newMechanism) {
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

    if (trace != null) {
      final DDSpan rootSpan = trace.getRootSpan();
      if (null != rootSpan && rootSpan.context() != this) {
        return rootSpan.context().setSamplingPriority(newPriority, newMechanism);
      }
    }

    int newSamplingDecision = SamplingDecision.create(newPriority, newMechanism);
    if (!SAMPLING_DECISION_UPDATER.compareAndSet(
        this, SamplingDecision.UNSET_UNKNOWN, newSamplingDecision)) {
      if (log.isDebugEnabled()) {
        log.debug(
            "samplingPriority locked at priority: {} mechanism: {}. Refusing to set to priority: {} mechanism: {}",
            SamplingDecision.priority(samplingDecision),
            SamplingDecision.mechanism(samplingDecision),
            SamplingDecision.priority(newSamplingDecision),
            SamplingDecision.mechanism(newSamplingDecision));
      }
      return false;
    }
    return true;
  }

  /** @return the sampling priority of this span's trace, or null if no priority has been set */
  public int getSamplingPriority() {
    // TODO find usages and see whether returning SamplingDecision is needed @YG
    final DDSpan rootSpan = trace.getRootSpan();
    if (null != rootSpan && rootSpan.context() != this) {
      return rootSpan.context().getSamplingPriority();
    }

    return SamplingDecision.priority(samplingDecision);
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

    return SAMPLING_DECISION_UPDATER.get(this) != SamplingDecision.UNSET_UNKNOWN;
  }

  public CharSequence getOrigin() {
    final DDSpan rootSpan = trace.getRootSpan();
    if (null != rootSpan) {
      return rootSpan.context().origin;
    } else {
      return origin;
    }
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

  public RequestContext<Object> getRequestContext() {
    return null == requestContextData ? null : this;
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
    this.origin = origin;
  }

  public void setMetric(final CharSequence key, final Number value) {
    synchronized (unsafeTags) {
      unsafeSetTag(key.toString(), value);
    }
  }

  /**
   * Add a tag to the span. Tags are not propagated to the children
   *
   * @param tag the tag-name
   * @param value the value of the tag. tags with null values are ignored.
   */
  public void setTag(final String tag, final Object value) {
    if (null == value || "".equals(value)) {
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
        synchronized (unsafeTags) {
          return unsafeGetTag(key);
        }
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
      tags.put(DDTags.THREAD_NAME, threadName.toString());
      if (samplingDecision != SamplingDecision.UNSET_UNKNOWN) {
        tags.put(SAMPLE_RATE_KEY, SamplingDecision.priority(samplingDecision));
      }
      if (httpStatusCode != 0) {
        tags.put(Tags.HTTP_STATUS, (int) httpStatusCode);
      }
      return Collections.unmodifiableMap(tags);
    }
  }

  public void processTagsAndBaggage(final MetadataConsumer consumer) {
    synchronized (unsafeTags) {
      consumer.accept(
          new Metadata(
              threadId,
              threadName,
              postProcessor.processTags(unsafeTags),
              baggageItems,
              (samplingDecision != SamplingDecision.UNSET_UNKNOWN
                  ? SamplingDecision.priority(samplingDecision)
                  : getSamplingPriority()),
              // TODO do we also need to pass samplingMechanism in there? @YG
              measured,
              topLevel,
              httpStatusCode == 0 ? null : HTTP_STATUSES.get(httpStatusCode),
              getOrigin())); // Get origin from rootSpan.context
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
  public Object getData() {
    return requestContextData;
  }

  @Override
  public TraceSegment getTraceSegment() {
    return this;
  }

  /** TraceSegment Implementation */
  @Override
  public void setTagTop(String key, Object value) {
    getTopContext().setTagCurrent(key, value);
  }

  @Override
  public void setTagCurrent(String key, Object value) {
    this.setTag(key, value);
  }

  @Override
  public void setDataTop(String key, Object value) {
    getTopContext().setDataCurrent(key, value);
  }

  @Override
  public void setDataCurrent(String key, Object value) {
    // TODO is this decided?
    String tagKey = "_dd." + key + ".json";
    this.setTag(tagKey, value);
  }

  private DDSpanContext getTopContext() {
    DDSpan span = trace.getRootSpan();
    return null != span ? span.context() : this;
  }
}
