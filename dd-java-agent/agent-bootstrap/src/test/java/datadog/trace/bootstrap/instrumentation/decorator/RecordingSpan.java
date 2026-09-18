package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ImmutableSpan;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A recording {@link AgentSpan} test double for decorator {@code afterStart} tests. Rather than
 * verifying individual mock interactions, it accumulates the state a decorator applies (span type,
 * tags, integration name, service name, measured flag, analytics metric) so a test can assert the
 * resulting span state as a whole -- see {@link ExpectedSpanState}.
 *
 * <p>Only the mutators {@code afterStart} exercises are recorded; every other {@link AgentSpan}
 * method inherits the inert {@link ImmutableSpan} / noop behavior.
 */
final class RecordingSpan extends ImmutableSpan {
  private final RecordingSpanContext context = new RecordingSpanContext();
  private final Map<String, String> tags = new LinkedHashMap<>();

  private CharSequence spanType;
  private String serviceName;
  private CharSequence serviceNameSource;
  private boolean serviceNameSet;
  private boolean measured;
  private boolean metricSet;
  private TagMap.EntryReader metric;

  // ----- recorded mutators -----

  @Override
  public AgentSpan setSpanType(CharSequence type) {
    this.spanType = type;
    return this;
  }

  @Override
  public AgentSpan setAllTags(Map<String, ?> map) {
    if (map == null || map.isEmpty()) {
      return this;
    }
    if (map instanceof TagMap) {
      ((TagMap) map)
          .forEach(reader -> tags.put(reader.tag(), String.valueOf(reader.objectValue())));
    } else {
      for (Map.Entry<String, ?> entry : map.entrySet()) {
        tags.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }
    return this;
  }

  @Override
  public AgentSpan setTag(TagMap.EntryReader entry) {
    if (entry != null) {
      tags.put(entry.tag(), String.valueOf(entry.objectValue()));
    }
    return this;
  }

  @Override
  public AgentSpan setTag(String key, CharSequence value) {
    tags.put(key, String.valueOf(value));
    return this;
  }

  @Override
  public AgentSpan setTag(String key, String value) {
    tags.put(key, value);
    return this;
  }

  @Override
  public AgentSpan setTag(String key, Object value) {
    tags.put(key, String.valueOf(value));
    return this;
  }

  @Override
  public void setServiceName(String serviceName, CharSequence source) {
    this.serviceNameSet = true;
    this.serviceName = serviceName;
    this.serviceNameSource = source;
  }

  @Override
  public AgentSpan setMeasured(boolean measured) {
    this.measured = measured;
    return this;
  }

  @Override
  public AgentSpan setMetric(TagMap.EntryReader metricEntry) {
    this.metricSet = true;
    this.metric = metricEntry;
    return this;
  }

  @Override
  public AgentSpanContext spanContext() {
    return context;
  }

  // ----- recorded state accessors -----

  CharSequence recordedSpanType() {
    return spanType;
  }

  Map<String, String> recordedTags() {
    return tags;
  }

  CharSequence recordedIntegrationName() {
    return context.recordedIntegrationName();
  }

  boolean serviceNameSet() {
    return serviceNameSet;
  }

  String recordedServiceName() {
    return serviceName;
  }

  CharSequence recordedServiceNameSource() {
    return serviceNameSource;
  }

  boolean recordedMeasured() {
    return measured;
  }

  boolean metricSet() {
    return metricSet;
  }

  TagMap.EntryReader recordedMetric() {
    return metric;
  }

  // ----- inert reads (mirror NoopSpan) -----

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return 0;
  }

  @Override
  public RequestBlockingAction getRequestBlockingAction() {
    return null;
  }

  @Override
  public boolean isError() {
    return false;
  }

  @Override
  public Object getTag(String key) {
    return tags.get(key);
  }

  @Override
  public long getStartTime() {
    return 0;
  }

  @Override
  public long getDurationNano() {
    return 0;
  }

  @Override
  public String getOperationName() {
    return null;
  }

  @Override
  public String getServiceName() {
    return serviceName;
  }

  @Override
  public CharSequence getResourceName() {
    return null;
  }

  @Override
  public RequestContext getRequestContext() {
    return RequestContext.Noop.INSTANCE;
  }

  @Override
  public Integer getSamplingPriority() {
    return (int) PrioritySampling.UNSET;
  }

  @Override
  public String getSpanType() {
    return spanType == null ? null : spanType.toString();
  }

  @Override
  public TagMap getTags() {
    return TagMap.EMPTY;
  }

  @Override
  public AgentSpan getRootSpan() {
    return this;
  }

  @Override
  public short getHttpStatusCode() {
    return 0;
  }

  @Override
  public AgentSpan getLocalRootSpan() {
    return this;
  }

  @Override
  public boolean isSameTrace(AgentSpan otherSpan) {
    return otherSpan == this;
  }

  @Override
  public String getBaggageItem(String key) {
    return null;
  }

  @Override
  public String getSpanName() {
    return "";
  }

  @Override
  public boolean hasResourceName() {
    return false;
  }

  @Override
  public byte getResourceNamePriority() {
    return Byte.MAX_VALUE;
  }

  @Override
  public TraceConfig traceConfig() {
    return AgentTracer.NoopTraceConfig.INSTANCE;
  }

  @Override
  public boolean isOutbound() {
    return false;
  }
}
