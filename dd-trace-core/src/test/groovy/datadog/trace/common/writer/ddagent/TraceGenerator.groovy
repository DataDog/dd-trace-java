package datadog.trace.common.writer.ddagent

import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.DDSpanData
import datadog.trace.core.TagsAndBaggageConsumer

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

class TraceGenerator {

  static List<List<DDSpanData>> generateRandomTraces(int howMany, boolean lowCardinality) {
    List<List<DDSpanData>> traces = new ArrayList<>(howMany)
    for (int i = 0; i < howMany; ++i) {
      int traceSize = ThreadLocalRandom.current().nextInt(2, 20)
      traces.add(generateRandomTrace(traceSize, lowCardinality))
    }
    return traces
  }

  private static List<DDSpanData> generateRandomTrace(int size, boolean lowCardinality) {
    List<DDSpanData> trace = new ArrayList<>(size)
    long traceId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
    for (int i = 0; i < size; ++i) {
      trace.add(randomSpan(traceId, lowCardinality))
    }
    return trace
  }

  private static DDSpanData randomSpan(long traceId, boolean lowCardinality) {
    Map<String, String> baggage = new HashMap<>()
    baggage.put("baggage-key", lowCardinality ? "x" : UUID.randomUUID().toString())
    if (ThreadLocalRandom.current().nextBoolean()) {
      baggage.put("tag.1", "bar")
    }
    Map<String, Object> tags = new HashMap<>()
    tags.put("tag.1", ThreadLocalRandom.current().nextBoolean() ? "foo" : new String(new char[2000]))
    tags.put("tag.2", lowCardinality ? "y" : UUID.randomUUID())
    Map<String, Number> metrics = new HashMap<>()
    metrics.put("metric.1", ThreadLocalRandom.current().nextInt())
    return new PojoSpan(
      "service-" + ThreadLocalRandom.current().nextInt(lowCardinality? 1 : 10),
      "operation-" + ThreadLocalRandom.current().nextInt(lowCardinality? 1 : 100),
      "resource-" + ThreadLocalRandom.current().nextInt(lowCardinality? 1 : 100),
      DDId.from(traceId),
      DDId.generate(),
      DDId.ZERO,
      TimeUnit.MICROSECONDS.toMicros(System.currentTimeMillis()),
      ThreadLocalRandom.current().nextLong(500, 10_000_000),
      ThreadLocalRandom.current().nextInt(2),
      metrics,
      baggage,
      tags,
      "type-" + ThreadLocalRandom.current().nextInt(lowCardinality? 1 : 100))
  }

  static class PojoSpan implements DDSpanData {

    private final CharSequence serviceName
    private final CharSequence operationName
    private final CharSequence resourceName
    private final DDId traceId
    private final DDId spanId
    private final DDId parentId
    private final long start
    private final long duration
    private final int error
    private final Map<String, Number> metrics
    private final Map<String, String> baggage
    private final Map<String, Object> tags
    private final String type

    PojoSpan(
      String serviceName,
      String operationName,
      CharSequence resourceName,
      DDId traceId,
      DDId spanId,
      DDId parentId,
      long start,
      long duration,
      int error,
      Map<String, Number> metrics,
      Map<String, String> baggage,
      Map<String, Object> tags,
      String type) {
      this.serviceName = UTF8BytesString.create(serviceName)
      this.operationName = UTF8BytesString.create(operationName)
      this.resourceName = UTF8BytesString.create(resourceName)
      this.traceId = traceId
      this.spanId = spanId
      this.parentId = parentId
      this.start = start
      this.duration = duration
      this.error = error
      this.metrics = metrics
      this.baggage = baggage
      this.tags = tags
      this.type = type
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
      return traceId
    }

    @Override
    DDId getSpanId() {
      return spanId
    }

    @Override
    DDId getParentId() {
      return parentId
    }

    @Override
    long getStartTime() {
      return start
    }

    @Override
    long getDurationNano() {
      return duration
    }

    @Override
    int getError() {
      return error
    }

    @Override
    Map<String, Number> getMetrics() {
      return metrics
    }

    @Override
    Map<String, String> getBaggage() {
      return baggage
    }

    @Override
    Map<String, Object> getTags() {
      return tags
    }

    @Override
    String getType() {
      return type
    }

    @Override
    void processTagsAndBaggage(TagsAndBaggageConsumer consumer) {
      consumer.accept(tags, baggage)
    }
  }
}
