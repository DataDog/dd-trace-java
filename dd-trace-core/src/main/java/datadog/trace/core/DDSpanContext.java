package datadog.trace.core;

import datadog.trace.api.DDId;
import datadog.trace.api.DDTags;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.taginterceptor.TagInterceptor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * SpanContext represents Span state that must propagate to descendant Spans and across process
 * boundaries.
 *
 * <p>SpanContext is logically divided into two pieces: (1) the user-level "Baggage" that propagates
 * across Span boundaries and (2) any Datadog fields that are needed to identify or contextualize
 * the associated Span instance
 */
@Slf4j
public class DDSpanContext implements AgentSpan.Context {
  public static final String PRIORITY_SAMPLING_KEY = "_sampling_priority_v1";
  public static final String SAMPLE_RATE_KEY = "_sample_rate";
  public static final String ORIGIN_KEY = "_dd.origin";

  private static final DDCache<String, UTF8BytesString> THREAD_NAMES =
      DDCaches.newFixedSizeCache(256);

  private static final Map<CharSequence, Number> EMPTY_METRICS = Collections.emptyMap();
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
  /** Each span have an operation name describing the current span */
  private volatile CharSequence operationName;
  /** The type of the span. If null, the Datadog Agent will report as a custom */
  private volatile CharSequence spanType;
  /** True indicates that the span reports an error */
  private volatile boolean errorFlag;

  private volatile boolean measuredFlag;

  private volatile boolean topLevel;
  /**
   * When true, the samplingPriority cannot be changed. This prevents the sampling flag from
   * changing after the context has propagated.
   *
   * <p>For thread safety, this boolean is only modified or accessed under instance lock.
   */
  private boolean samplingPriorityLocked = false;

  private volatile byte samplingPriorityV1 = PrioritySampling.UNSET;
  /** The origin of the trace. (eg. Synthetics) */
  private final String origin;
  /** Metrics on the span - access synchronized on the spanId */
  private volatile Map<CharSequence, Number> metrics = EMPTY_METRICS;

  public DDSpanContext(
      final DDId traceId,
      final DDId spanId,
      final DDId parentId,
      final CharSequence parentServiceName,
      final String serviceName,
      final CharSequence operationName,
      final CharSequence resourceName,
      final int samplingPriority,
      final String origin,
      final Map<String, String> baggageItems,
      final boolean errorFlag,
      final CharSequence spanType,
      final int tagsSize,
      final PendingTrace trace) {

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

    if (samplingPriority != PrioritySampling.UNSET) {
      setSamplingPriority(samplingPriority);
    }

    if (origin != null) {
      this.unsafeTags.put(ORIGIN_KEY, origin);
    }
    // Additional Metadata
    final Thread current = Thread.currentThread();
    this.threadId = current.getId();
    this.threadName = THREAD_NAMES.computeIfAbsent(current.getName(), Functions.UTF8_ENCODE);
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

  public CharSequence getResourceName() {
    return isResourceNameSet() ? resourceName : operationName;
  }

  public boolean isResourceNameSet() {
    return resourceName != null && resourceName.length() != 0;
  }

  public boolean hasResourceName() {
    return isResourceNameSet() || getTag(DDTags.RESOURCE_NAME) != null;
  }

  public void setResourceName(final CharSequence resourceName) {
    this.resourceName = resourceName;
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
    return measuredFlag;
  }

  public void setMeasured(boolean measured) {
    if (measured != measuredFlag) {
      measuredFlag = measured;
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

  /** @return if sampling priority was set by this method invocation */
  public boolean setSamplingPriority(final int newPriority) {
    if (newPriority == PrioritySampling.UNSET) {
      log.debug("{}: Refusing to set samplingPriority to UNSET", this);
      return false;
    }

    if (trace != null) {
      final DDSpan rootSpan = trace.getRootSpan();
      if (null != rootSpan && rootSpan.context() != this) {
        return rootSpan.context().setSamplingPriority(newPriority);
      }
    }

    // sync with lockSamplingPriority
    synchronized (this) {
      if (samplingPriorityLocked) {
        if (log.isDebugEnabled()) {
          log.debug(
              "samplingPriority locked at {}. Refusing to set to {}",
              samplingPriorityV1,
              newPriority);
        }
        return false;
      } else {
        this.samplingPriorityV1 = (byte) newPriority;
        if (log.isDebugEnabled()) {
          log.debug("Set sampling priority to {}", samplingPriorityV1);
        }
        return true;
      }
    }
  }

  /** @return the sampling priority of this span's trace, or null if no priority has been set */
  public int getSamplingPriority() {
    final DDSpan rootSpan = trace.getRootSpan();
    if (null != rootSpan && rootSpan.context() != this) {
      return rootSpan.context().getSamplingPriority();
    }

    return samplingPriorityV1;
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
  public boolean lockSamplingPriority() {
    final DDSpan rootSpan = trace.getRootSpan();
    if (null != rootSpan && rootSpan.context() != this) {
      return rootSpan.context().lockSamplingPriority();
    }

    // sync with setSamplingPriority
    synchronized (this) {
      if (samplingPriorityV1 == PrioritySampling.UNSET) {
        log.debug("{} : refusing to lock unset samplingPriority", this);
      } else if (!samplingPriorityLocked) {
        samplingPriorityLocked = true;
        if (log.isDebugEnabled()) {
          log.debug("{} : locked samplingPriority to {}", this, samplingPriorityV1);
        }
      }
      return samplingPriorityLocked;
    }
  }

  public String getOrigin() {
    final DDSpan rootSpan = trace.getRootSpan();
    if (null != rootSpan) {
      return rootSpan.context().origin;
    } else {
      return origin;
    }
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

  @Deprecated
  public CoreTracer getTracer() {
    return trace.getTracer();
  }

  public Map<CharSequence, Number> getUnsafeMetrics() {
    return metrics;
  }

  public void setMetric(final CharSequence key, final Number value) {
    if (metrics == EMPTY_METRICS) {
      // synchronize on spanId to not contend with sample rates being set
      synchronized (spanId) {
        if (metrics == EMPTY_METRICS) {
          metrics = new HashMap<>(4);
          metrics.put(key, value instanceof Float ? value.doubleValue() : value);
          return;
        }
      }
    }
    synchronized (spanId) {
      metrics.put(key, value instanceof Float ? value.doubleValue() : value);
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

  void setAllTags(final Map<String, ? extends Object> map) {
    if (map == null || map.isEmpty()) {
      return;
    }

    TagInterceptor tagInterceptor = trace.getTracer().getTagInterceptor();
    synchronized (unsafeTags) {
      for (final Map.Entry<String, ? extends Object> tag : map.entrySet()) {
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
      return Collections.unmodifiableMap(tags);
    }
  }

  public void processTagsAndBaggage(final MetadataConsumer consumer) {
    synchronized (unsafeTags) {
      consumer.accept(new Metadata(threadId, threadName, unsafeTags, baggageItems));
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
            .append(getResourceName())
            .append(" metrics=");

    synchronized (spanId) {
      Map<CharSequence, Number> metricsSnapshot = new TreeMap<>(getUnsafeMetrics());
      if (samplingPriorityV1 != PrioritySampling.UNSET) {
        metricsSnapshot.put(PRIORITY_SAMPLING_KEY, samplingPriorityV1);
      }
      s.append(metricsSnapshot);
    }
    if (errorFlag) {
      s.append(" *errored*");
    }

    synchronized (unsafeTags) {
      s.append(" tags=").append(new TreeMap<>(getTags()));
    }
    return s.toString();
  }
}
