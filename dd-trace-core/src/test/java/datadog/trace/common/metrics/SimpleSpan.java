package datadog.trace.common.metrics;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.MetadataConsumer;
import java.util.HashMap;
import java.util.Map;

public class SimpleSpan implements CoreSpan<SimpleSpan> {

  private final String serviceName;
  private final String operationName;
  private final CharSequence resourceName;
  private final CharSequence serviceNameSource;
  private final String type;
  private final boolean measured;
  private final boolean topLevel;
  private final boolean traceRoot;
  private final boolean error;
  private final short statusCode;
  private final long duration;
  private final long startTime;
  private final long longRunningVersion;
  private final Map<Object, Object> tags = new HashMap<>();

  public SimpleSpan(
      String serviceName,
      String operationName,
      CharSequence resourceName,
      String type,
      boolean measured,
      boolean topLevel,
      boolean error,
      long startTime,
      long duration,
      int statusCode) {
    this(
        serviceName,
        operationName,
        resourceName,
        type,
        measured,
        topLevel,
        error,
        startTime,
        duration,
        statusCode,
        false,
        0,
        null);
  }

  public SimpleSpan(
      String serviceName,
      String operationName,
      CharSequence resourceName,
      String type,
      boolean measured,
      boolean topLevel,
      boolean error,
      long startTime,
      long duration,
      int statusCode,
      boolean traceRoot,
      int longRunningVersion,
      CharSequence serviceNameSource) {
    this.serviceName = serviceName;
    this.operationName = operationName;
    this.resourceName = resourceName;
    this.serviceNameSource = serviceNameSource;
    this.type = type;
    this.measured = measured;
    this.topLevel = topLevel;
    this.traceRoot = traceRoot;
    this.error = error;
    this.startTime = startTime;
    this.duration = duration;
    this.statusCode = (short) statusCode;
    this.longRunningVersion = longRunningVersion;
  }

  @Override
  public SimpleSpan getLocalRootSpan() {
    return this;
  }

  @Override
  public String getServiceName() {
    return serviceName;
  }

  @Override
  public CharSequence getServiceNameSource() {
    return serviceNameSource;
  }

  @Override
  public CharSequence getOperationName() {
    return operationName;
  }

  @Override
  public CharSequence getResourceName() {
    return resourceName;
  }

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public long getParentId() {
    return traceRoot ? DDSpanId.ZERO : 1L;
  }

  @Override
  public long getStartTime() {
    return startTime;
  }

  @Override
  public long getDurationNano() {
    return duration;
  }

  @Override
  public int getError() {
    return error ? 1 : 0;
  }

  @Override
  public short getHttpStatusCode() {
    return statusCode;
  }

  @Override
  public CharSequence getOrigin() {
    return null;
  }

  @Override
  public SimpleSpan setMeasured(boolean measured) {
    return this;
  }

  @Override
  public SimpleSpan setErrorMessage(String errorMessage) {
    return this;
  }

  @Override
  public SimpleSpan addThrowable(Throwable error) {
    return this;
  }

  @Override
  public SimpleSpan setTag(String tag, String value) {
    return setTag(tag, (Object) value);
  }

  @Override
  public SimpleSpan setTag(String tag, boolean value) {
    return setTag(tag, (Object) value);
  }

  @Override
  public SimpleSpan setTag(String tag, int value) {
    return setTag(tag, (Object) value);
  }

  @Override
  public SimpleSpan setTag(String tag, long value) {
    return setTag(tag, (Object) value);
  }

  @Override
  public SimpleSpan setTag(String tag, double value) {
    return setTag(tag, (Object) value);
  }

  @Override
  public SimpleSpan setTag(String tag, Number value) {
    return setTag(tag, (Object) value);
  }

  @Override
  public SimpleSpan setTag(String tag, CharSequence value) {
    return setTag(tag, (Object) value);
  }

  @Override
  public SimpleSpan setTag(String tag, Object value) {
    tags.put(tag, value);
    return this;
  }

  @Override
  public SimpleSpan removeTag(String tag) {
    tags.remove(tag);
    return this;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <U> U getTag(CharSequence name, U defaultValue) {
    Object tagValue = tags.get(String.valueOf(name));
    return tagValue != null ? (U) tagValue : defaultValue;
  }

  @Override
  public <U> U getTag(CharSequence name) {
    return getTag(name, null);
  }

  @Override
  public boolean hasSamplingPriority() {
    return false;
  }

  @Override
  public boolean isMeasured() {
    return measured;
  }

  @Override
  public boolean isTopLevel() {
    return topLevel;
  }

  @Override
  public boolean isForceKeep() {
    return false;
  }

  @Override
  public CharSequence getType() {
    return type;
  }

  @Override
  public void processServiceTags() {}

  @Override
  public void processTagsAndBaggage(MetadataConsumer consumer) {}

  @Override
  public SimpleSpan setSamplingPriority(int samplingPriority, int samplingMechanism) {
    return this;
  }

  @Override
  public SimpleSpan setSamplingPriority(
      int samplingPriority, CharSequence rate, double sampleRate, int samplingMechanism) {
    return this;
  }

  @Override
  public SimpleSpan setSpanSamplingPriority(double rate, int limit) {
    return this;
  }

  @Override
  public SimpleSpan setMetric(CharSequence name, int value) {
    return this;
  }

  @Override
  public SimpleSpan setMetric(CharSequence name, long value) {
    return this;
  }

  @Override
  public SimpleSpan setMetric(CharSequence name, float value) {
    return this;
  }

  @Override
  public SimpleSpan setMetric(CharSequence name, double value) {
    return this;
  }

  @Override
  public SimpleSpan setFlag(CharSequence name, boolean value) {
    return this;
  }

  @Override
  public int samplingPriority() {
    return 0;
  }

  @Override
  public Map<String, Object> getMetaStruct() {
    return new HashMap<>();
  }

  @Override
  public SimpleSpan setMetaStruct(String field, Object value) {
    return this;
  }

  @Override
  public int getLongRunningVersion() {
    return (int) longRunningVersion;
  }
}
