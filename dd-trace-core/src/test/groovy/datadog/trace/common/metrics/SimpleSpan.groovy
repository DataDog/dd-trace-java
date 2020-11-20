package datadog.trace.common.metrics

import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentSpanData
import datadog.trace.bootstrap.instrumentation.api.TagsAndBaggageConsumer

class SimpleSpan implements AgentSpanData {

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
}
