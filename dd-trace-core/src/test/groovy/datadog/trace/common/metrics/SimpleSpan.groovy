package datadog.trace.common.metrics

import datadog.trace.api.DDId
import datadog.trace.core.CoreSpan
import datadog.trace.core.TagsAndBaggageConsumer

class SimpleSpan implements CoreSpan<SimpleSpan> {

  private final String serviceName
  private final String operationName
  private final String resourceName
  private final String type
  private final boolean measured
  private final boolean error

  private final long duration
  private final long startTime

  SimpleSpan(String serviceName,
             String operationName,
             String resourceName,
             String type,
             boolean measured,
             boolean error,
             long startTime,
             long duration) {
    this.serviceName = serviceName
    this.operationName = operationName
    this.resourceName = resourceName
    this.type = type
    this.measured = measured
    this.error = error
    this.startTime = startTime
    this.duration = duration
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
  DDId getTraceId() {
    return DDId.ZERO
  }

  @Override
  DDId getSpanId() {
    return DDId.ZERO
  }

  @Override
  DDId getParentId() {
    return DDId.ZERO
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
    return this
  }

  @Override
  SimpleSpan setTag(String tag, boolean value) {
    return this
  }

  @Override
  SimpleSpan setTag(String tag, int value) {
    return this
  }

  @Override
  SimpleSpan setTag(String tag, long value) {
    return this
  }

  @Override
  SimpleSpan setTag(String tag, double value) {
    return this
  }

  @Override
  SimpleSpan setTag(String tag, Number value) {
    return this
  }

  @Override
  SimpleSpan setTag(String tag, CharSequence value) {
    return this
  }

  @Override
  SimpleSpan setTag(String tag, Object value) {
    return this
  }

  @Override
  SimpleSpan removeTag(String tag) {
    return this
  }

  @Override
  <U> U getTag(CharSequence name, U defaultValue) {
    return defaultValue
  }

  @Override
  <U> U getTag(CharSequence name) {
    return null
  }

  @Override
  boolean isMeasured() {
    return measured
  }

  @Override
  Map<CharSequence, Number> getMetrics() {
    return null
  }

  @Override
  CharSequence getType() {
    return type
  }

  @Override
  void processTagsAndBaggage(TagsAndBaggageConsumer consumer) {

  }

  @Override
  SimpleSpan setSamplingPriority(int samplingPriority) {
    return this
  }

  @Override
  SimpleSpan setSamplingPriority(int samplingPriority, CharSequence rate, double sampleRate) {
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
}
