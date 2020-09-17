package datadog.trace.core;

import com.google.common.collect.ImmutableMap;
import datadog.trace.api.DDId;
import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.taginterceptor.AbstractTagInterceptor;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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

  private static final Map<String, Number> EMPTY_METRICS = Collections.emptyMap();

  // Shared with other span contexts
  /** For technical reasons, the ref to the original tracer */
  private final CoreTracer tracer;

  /** The collection of all span related to this one */
  private final PendingTrace trace;

  /** Baggage is associated with the whole trace and shared with other spans */
  private final Map<String, String> baggageItems;

  // Not Shared with other span contexts
  private final DDId traceId;
  private final DDId spanId;
  private final DDId parentId;

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
  private volatile String spanType;
  /** True indicates that the span reports an error */
  private volatile boolean errorFlag;
  /**
   * When true, the samplingPriority cannot be changed. This prevents the sampling flag from
   * changing after the context has propagated.
   *
   * <p>For thread safety, this boolean is only modified or accessed under instance lock.
   */
  private boolean samplingPriorityLocked = false;
  /** The origin of the trace. (eg. Synthetics) */
  private final String origin;
  /** Metrics on the span */
  private final AtomicReference<Map<String, Number>> metrics = new AtomicReference<>();

  private final Map<String, String> serviceNameMappings;

  private final ExclusiveSpan exclusiveSpan;

  public DDSpanContext(
      final DDId traceId,
      final DDId spanId,
      final DDId parentId,
      final String serviceName,
      final CharSequence operationName,
      final CharSequence resourceName,
      final int samplingPriority,
      final String origin,
      final Map<String, String> baggageItems,
      final boolean errorFlag,
      final String spanType,
      final int tagsSize,
      final PendingTrace trace,
      final CoreTracer tracer,
      final Map<String, String> serviceNameMappings) {

    assert tracer != null;
    assert trace != null;
    this.tracer = tracer;
    this.trace = trace;

    assert traceId != null;
    assert spanId != null;
    assert parentId != null;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;

    if (baggageItems == null) {
      this.baggageItems = new ConcurrentHashMap<>(0);
    } else {
      this.baggageItems = new ConcurrentHashMap<>(baggageItems);
    }

    // The +3 is the magic number from the tags below that we set at the end,
    // and "* 4 / 3" is to make sure that we don't resize immediately
    int capacity = ((tagsSize <= 0 ? 3 : tagsSize + 3) * 4) / 3;
    this.unsafeTags = new HashMap<>(capacity);

    this.serviceNameMappings = serviceNameMappings;
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
    Thread current = Thread.currentThread();
    this.unsafeTags.put(DDTags.THREAD_NAME, current.getName());
    this.unsafeTags.put(DDTags.THREAD_ID, current.getId());

    // It is safe that we let `this` escape into the ExclusiveSpan constructor,
    // since ExclusiveSpan is only a wrapper and the instance can only be accessed from
    // this DDSpanContext.
    this.exclusiveSpan = new ExclusiveSpan(this);
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
    String mappedServiceName = serviceNameMappings.get(serviceName);
    this.serviceName = mappedServiceName == null ? serviceName : mappedServiceName;
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
    this.errorFlag = errorFlag;
  }

  public String getSpanType() {
    return spanType;
  }

  public void setSpanType(final String spanType) {
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
        log.debug(
            "samplingPriority locked at {}. Refusing to set to {}",
            getMetrics().get(PRIORITY_SAMPLING_KEY),
            newPriority);
        return false;
      } else {
        setMetric(PRIORITY_SAMPLING_KEY, newPriority);
        log.debug("Set sampling priority to {}", getMetrics().get(PRIORITY_SAMPLING_KEY));
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

    final Number val = getMetrics().get(PRIORITY_SAMPLING_KEY);
    return null == val ? PrioritySampling.UNSET : val.intValue();
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
      if (getMetrics().get(PRIORITY_SAMPLING_KEY) == null) {
        log.debug("{} : refusing to lock unset samplingPriority", this);
      } else if (!samplingPriorityLocked) {
        samplingPriorityLocked = true;
        log.debug(
            "{} : locked samplingPriority to {}", this, getMetrics().get(PRIORITY_SAMPLING_KEY));
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
    return tracer;
  }

  public Map<String, Number> getMetrics() {
    final Map<String, Number> metrics = this.metrics.get();
    return metrics == null ? EMPTY_METRICS : metrics;
  }

  public void setMetric(final String key, final Number value) {
    if (metrics.get() == null) {
      metrics.compareAndSet(null, new ConcurrentHashMap<String, Number>());
    }
    if (value instanceof Float) {
      metrics.get().put(key, value.doubleValue());
    } else {
      metrics.get().put(key, value);
    }
  }
  /**
   * Add a tag to the span. Tags are not propagated to the children
   *
   * @param tag the tag-name
   * @param value the value of the tag. tags with null values are ignored.
   */
  public void setTag(final String tag, final Object value) {
    synchronized (unsafeTags) {
      unsafeSetTag(tag, value);
    }
  }

  void setAllTags(final Map<String, ? extends Object> map) {
    if (map == null || map.isEmpty()) {
      return;
    }

    synchronized (unsafeTags) {
      for (final Map.Entry<String, ? extends Object> tag : map.entrySet()) {
        unsafeSetTag(tag.getKey(), tag.getValue());
      }
    }
  }

  void unsafeSetTag(final String tag, final Object value) {
    if (value == null || (value instanceof String && ((String) value).isEmpty())) {
      unsafeTags.remove(tag);
      return;
    }

    boolean addTag = true;

    // Call interceptors
    final List<AbstractTagInterceptor> interceptors = tracer.getSpanTagInterceptors(tag);
    if (interceptors != null) {
      ExclusiveSpan span = exclusiveSpan;
      for (final AbstractTagInterceptor interceptor : interceptors) {
        try {
          addTag &= interceptor.shouldSetTag(span, tag, value);
        } catch (final Throwable ex) {
          log.debug(
              "Could not intercept the span interceptor={}: {}",
              interceptor.getClass().getSimpleName(),
              ex.getMessage());
        }
      }
    }

    if (addTag) {
      unsafeTags.put(tag, value);
    }
  }

  Object getTag(final String key) {
    synchronized (unsafeTags) {
      return unsafeGetTag(key);
    }
  }

  Object unsafeGetTag(final String tag) {
    return unsafeTags.get(tag);
  }

  Object getAndRemoveTag(final String tag) {
    synchronized (unsafeTags) {
      return unsafeGetAndRemoveTag(tag);
    }
  }

  Object unsafeGetAndRemoveTag(final String tag) {
    return unsafeTags.remove(tag);
  }

  public Map<String, Object> getTags() {
    synchronized (unsafeTags) {
      return ImmutableMap.copyOf(unsafeTags);
    }
  }

  public void processTagsAndBaggage(TagsAndBaggageConsumer consumer) {
    synchronized (unsafeTags) {
      consumer.accept(unsafeTags, baggageItems);
    }
  }

  public void processExclusiveSpan(ExclusiveSpan.Consumer consumer) {
    synchronized (unsafeTags) {
      consumer.accept(exclusiveSpan);
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
            .append("] trace=")
            .append(getServiceName())
            .append("/")
            .append(getOperationName())
            .append("/")
            .append(getResourceName())
            .append(" metrics=")
            .append(new TreeMap<>(getMetrics()));
    if (errorFlag) {
      s.append(" *errored*");
    }

    synchronized (unsafeTags) {
      s.append(" tags=").append(new TreeMap<>(unsafeTags));
    }
    return s.toString();
  }
}
