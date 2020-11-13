package datadog.trace.common.metrics

import datadog.trace.api.DDId
import datadog.trace.core.DDSpanData
import datadog.trace.core.TagsAndBaggageConsumer

class SimpleSpan implements DDSpanData {

  private final String serviceName
  private final String operationName
  private final String resourceName
  private final boolean measured
  private final boolean error

  private final long duration
  private final long startTime

  SimpleSpan(String serviceName,
             String operationName,
             String resourceName,
             boolean measured,
             boolean error,
             long startTime,
             long duration) {
    this.serviceName = serviceName
    this.operationName = operationName
    this.resourceName = resourceName
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
  boolean isMeasured() {
    return measured
  }

  @Override
  Map<CharSequence, Number> getMetrics() {
    return null
  }

  @Override
  Map<String, String> getBaggage() {
    return null
  }

  @Override
  Map<String, Object> getTags() {
    return null
  }

  @Override
  CharSequence getType() {
    return null
  }

  @Override
  void processTagsAndBaggage(TagsAndBaggageConsumer consumer) {

  }
}
