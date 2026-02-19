package datadog.trace.common.metrics

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.core.CoreSpan
import datadog.trace.core.MetadataConsumer

class SimpleSpan implements CoreSpan<SimpleSpan> {

  private final String serviceName
  private final String operationName
  private final CharSequence resourceName
  private final String type
  private final boolean measured
  private final boolean topLevel
  private final boolean traceRoot
  private final boolean error
  private final short statusCode

  private final long duration
  private final long startTime
  private final long longRunningVersion

  private final Map<Object, Object> tags = [:]

  SimpleSpan(
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
      boolean traceRoot = false,
      int longRunningVersion = 0
  ) {
    this.serviceName = serviceName
    this.operationName = operationName
    this.resourceName = resourceName
    this.type = type
    this.measured = measured
    this.topLevel = topLevel
    this.traceRoot = traceRoot
    this.error = error
    this.startTime = startTime
    this.duration = duration
    this.statusCode = (short) statusCode
    this.longRunningVersion = longRunningVersion
  }

  @Override
  SimpleSpan getLocalRootSpan() {
    return this
  }

  @Override
  String getServiceName() {
    return serviceName
  }

  @Override
  CharSequence getOperationName() {
    return operationName
  }

  @Override
  CharSequence getResourceName() {
    return resourceName
  }

  @Override
  DDTraceId getTraceId() {
    return DDTraceId.ZERO
  }

  @Override
  long getSpanId() {
    return DDSpanId.ZERO
  }

  @Override
  long getParentId() {
    return traceRoot ? DDSpanId.ZERO : 1L
  }

  @Override
  long getStartTime() {
    return startTime
  }

  @Override
  long getDurationNano() {
    return duration
  }

  @Override
  int getError() {
    return error ? 1 : 0
  }

  @Override
  short getHttpStatusCode() {
    return statusCode
  }

  @Override
  CharSequence getOrigin() {
    return null
  }

  @Override
  SimpleSpan setMeasured(boolean measured) {
    return this
  }

  @Override
  SimpleSpan setErrorMessage(String errorMessage) {
    return this
  }

  @Override
  SimpleSpan addThrowable(Throwable error) {
    return this
  }

  @Override
  SimpleSpan setTag(String tag, String value) {
    return setTag(tag, (Object) value)
  }

  @Override
  SimpleSpan setTag(String tag, boolean value) {
    return setTag(tag, (Object) value)
  }

  @Override
  SimpleSpan setTag(String tag, int value) {
    return setTag(tag, (Object) value)
  }

  @Override
  SimpleSpan setTag(String tag, long value) {
    return setTag(tag, (Object) value)
  }

  @Override
  SimpleSpan setTag(String tag, double value) {
    return setTag(tag, (Object) value)
  }

  @Override
  SimpleSpan setTag(String tag, Number value) {
    return setTag(tag, (Object) value)
  }

  @Override
  SimpleSpan setTag(String tag, CharSequence value) {
    return setTag(tag, (Object) value)
  }

  @Override
  SimpleSpan setTag(String tag, Object value) {
    tags.put(tag, value)
    return this
  }

  @Override
  SimpleSpan removeTag(String tag) {
    tags.remove(tag)
    return this
  }

  @Override
  <U> U getTag(CharSequence name, U defaultValue) {
    def tagValue = tags.get(String.valueOf(name)) ?: defaultValue
    return tagValue != null ? (U) tagValue : defaultValue
  }

  @Override
  <U> U getTag(CharSequence name) {
    return getTag(name, null)
  }

  @Override
  boolean hasSamplingPriority() {
    return false
  }

  @Override
  boolean isMeasured() {
    return measured
  }

  @Override
  boolean isTopLevel() {
    return topLevel
  }

  @Override
  boolean isForceKeep() {
    return false
  }

  @Override
  CharSequence getType() {
    return type
  }

  @Override
  void processServiceTags() {}

  @Override
  void processTagsAndBaggage(MetadataConsumer consumer) {}

  @Override
  SimpleSpan setSamplingPriority(int samplingPriority, int samplingMechanism) {
    return this
  }

  @Override
  SimpleSpan setSamplingPriority(int samplingPriority, CharSequence rate, double sampleRate, int samplingMechanism) {
    return this
  }

  @Override
  SimpleSpan setSpanSamplingPriority(double rate, int limit) {
    return this
  }

  @Override
  SimpleSpan setMetric(CharSequence name, int value) {
    return this
  }

  @Override
  SimpleSpan setMetric(CharSequence name, long value) {
    return this
  }

  @Override
  SimpleSpan setMetric(CharSequence name, float value) {
    return this
  }

  @Override
  SimpleSpan setMetric(CharSequence name, double value) {
    return this
  }

  @Override
  SimpleSpan setFlag(CharSequence name, boolean value) {
    return this
  }

  @Override
  int samplingPriority() {
    return 0
  }

  @Override
  Map<String, Object> getMetaStruct() {
    return [:]
  }

  @Override
  SimpleSpan setMetaStruct(String field, Object value) {
    return this
  }

  @Override
  int getLongRunningVersion() {
    return longRunningVersion
  }
}
