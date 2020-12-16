package datadog.trace.common.writer.ddagent

import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.CoreSpan
import datadog.trace.core.Metadata
import datadog.trace.core.MetadataConsumer

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

class TraceGenerator {

  static List<List<CoreSpan>> generateRandomTraces(int howMany, boolean lowCardinality) {
    List<List<CoreSpan>> traces = new ArrayList<>(howMany)
    for (int i = 0; i < howMany; ++i) {
      int traceSize = ThreadLocalRandom.current().nextInt(2, 20)
      traces.add(generateRandomTrace(traceSize, lowCardinality))
    }
    return traces
  }

  private static List<CoreSpan> generateRandomTrace(int size, boolean lowCardinality) {
    List<CoreSpan> trace = new ArrayList<>(size)
    long traceId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
    for (int i = 0; i < size; ++i) {
      trace.add(randomSpan(traceId, lowCardinality))
    }
    return trace
  }

  private static CoreSpan randomSpan(long traceId, boolean lowCardinality) {
    Map<String, String> baggage = new HashMap<>()
    if (ThreadLocalRandom.current().nextBoolean()) {
      baggage.put("baggage-key", lowCardinality ? "x" : randomString(100))
      if (ThreadLocalRandom.current().nextBoolean()) {
        baggage.put("tag.1", "bar")
        baggage.put("tag.2", "qux")
      }
    }
    Map<String, Object> tags = new HashMap<>()
    int tagCount = ThreadLocalRandom.current().nextInt(0, 20)
    for (int i = 0; i < tagCount; ++i) {
      tags.put("tag." + i, ThreadLocalRandom.current().nextBoolean() ? "foo" : randomString(2000))
      tags.put("tag.1." + i, lowCardinality ? "y" : UUID.randomUUID())
    }
    Map<String, Number> metrics = new HashMap<>()
    int metricCount = ThreadLocalRandom.current().nextInt(0, 20)
    for (int i = 0; i < metricCount; ++i) {
      metrics.put("metric." + i, ThreadLocalRandom.current().nextBoolean()
        ? ThreadLocalRandom.current().nextInt()
        : ThreadLocalRandom.current().nextDouble())
    }
    return new PojoSpan(
      "service-" + ThreadLocalRandom.current().nextInt(lowCardinality ? 1 : 10),
      "operation-" + ThreadLocalRandom.current().nextInt(lowCardinality ? 1 : 100),
      UTF8BytesString.create("resource-" + ThreadLocalRandom.current().nextInt(lowCardinality ? 1 : 100)),
      DDId.from(traceId),
      IdGenerationStrategy.RANDOM.generate(),
      DDId.ZERO,
      TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()),
      ThreadLocalRandom.current().nextLong(500, 10_000_000),
      ThreadLocalRandom.current().nextInt(2),
      metrics,
      baggage,
      tags,
      "type-" + ThreadLocalRandom.current().nextInt(lowCardinality ? 1 : 100),
      ThreadLocalRandom.current().nextBoolean())
  }

  private static String randomString(int maxLength) {
    char[] chars = new char[ThreadLocalRandom.current().nextInt(maxLength)]
    for (int i = 0; i < chars.length; ++i) {
      char next = (char) ThreadLocalRandom.current().nextInt((int) Character.MAX_VALUE)
      if (Character.isSurrogate(next)) {
        if (i < chars.length - 1) {
          chars[i++] = '\uD801'
          chars[i] = '\uDC01'
        } else {
          chars[i] = 'a'
        }
      } else {
        chars[i] = next
      }
    }
    return new String(chars)
  }

  static class PojoSpan implements CoreSpan<PojoSpan> {

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
    private final String type
    private final boolean measured
    private final Metadata metadata

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
      String type,
      boolean measured) {
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
      this.type = type
      this.measured = measured
      this.metadata = new Metadata(Thread.currentThread().getId(),
      UTF8BytesString.create(Thread.currentThread().getName()), tags, baggage)
    }

    @Override
    PojoSpan getLocalRootSpan() {
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
    PojoSpan setMeasured(boolean measured) {
      return this
    }

    @Override
    PojoSpan setErrorMessage(String errorMessage) {
      return this
    }

    @Override
    PojoSpan addThrowable(Throwable error) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, String value) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, boolean value) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, int value) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, long value) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, double value) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, Number value) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, CharSequence value) {
      return this
    }

    @Override
    PojoSpan setTag(String tag, Object value) {
      return this
    }

    @Override
    PojoSpan removeTag(String tag) {
      return this
    }

    @Override
    boolean isMeasured() {
      return measured
    }

    @Override
    boolean isTopLevel() {
      return false
    }

    @Override
    Map<String, Number> getUnsafeMetrics() {
      return metrics
    }

    Map<String, String> getBaggage() {
      return metadata.getBaggage()
    }

    Map<String, Object> getTags() {
      return metadata.getTags()
    }

    @Override
    String getType() {
      return type
    }

    @Override
    void processTagsAndBaggage(MetadataConsumer consumer) {
      consumer.accept(metadata)
    }

    @Override
    PojoSpan setSamplingPriority(int samplingPriority) {
      return this
    }

    @Override
    PojoSpan setSamplingPriority(int samplingPriority, CharSequence rate, double sampleRate) {
      return this
    }

    @Override
    PojoSpan setMetric(CharSequence name, int value) {
      return this
    }

    @Override
    PojoSpan setMetric(CharSequence name, long value) {
      return this
    }

    @Override
    PojoSpan setMetric(CharSequence name, float value) {
      return this
    }

    @Override
    PojoSpan setMetric(CharSequence name, double value) {
      return this
    }

    @Override
    PojoSpan setFlag(CharSequence name, boolean value) {
      return this
    }

    @Override
    int samplingPriority() {
      return 0
    }

    @Override
    <U> U getTag(CharSequence name, U defaultValue) {
      U value = getTag(name)
      return null == value ? defaultValue : value
    }

    @Override
    <U> U getTag(CharSequence name) {
      // replicate logic here because DDSpanContext has to pretend some of its
      // fields are elements of a map for backward compatibility reasons
      String tag = String.valueOf(name)
      Object value = null
      switch (tag) {
        case DDTags.THREAD_ID:
          value = metadata.getThreadId()
          break
        case DDTags.THREAD_NAME:
          value = metadata.getThreadName()
          break
        default:
          value = tags.get(tag)
      }
      return value as U
    }

    @Override
    boolean hasSamplingPriority() {
      return false
    }
  }
}
