package datadog.trace.common.writer

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.api.ProcessTags
import datadog.trace.api.TagMap
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.CoreSpan
import datadog.trace.core.Metadata
import datadog.trace.core.MetadataConsumer

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

import static datadog.trace.api.sampling.PrioritySampling.UNSET

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
      def spanType = "type-" + ThreadLocalRandom.current().nextInt(lowCardinality ? 1 : 100)
      trace.add(randomSpan(traceId, lowCardinality, spanType, Collections.emptyMap()))
    }
    return trace
  }

  private static final IdGenerationStrategy ID_GENERATION_STRATEGY = IdGenerationStrategy.fromName("RANDOM")

  static CoreSpan generateRandomSpan(CharSequence type, Map<String, Object> extraTags) {
    long traceId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
    return randomSpan(traceId, true, type, extraTags)
  }

  private static CoreSpan randomSpan(long traceId, boolean lowCardinality, CharSequence type, Map<String, Object> extraTags) {
    ThreadLocalRandom random = ThreadLocalRandom.current()
    Map<String, String> baggage = new HashMap<>()
    if (random.nextBoolean()) {
      baggage.put("baggage-key", lowCardinality ? "x" : randomString(100))
      if (random.nextBoolean()) {
        baggage.put("tag.1", "bar")
        baggage.put("tag.2", "qux")
      }
    }
    Map<String, Object> tags = new HashMap<>(extraTags)
    int tagCount = random.nextInt(0, 20)
    for (int i = 0; i < tagCount; ++i) {
      tags.put("tag." + i, random.nextBoolean() ? "foo" : randomString(2000))
      tags.put("tag.1." + i, lowCardinality ? "y" : UUID.randomUUID())
      tags.put("tag.2." + i, random.nextBoolean())
      switch (random.nextInt(8)) {
        case 0:
          tags.put("tag.3." + i, BigDecimal.valueOf(random.nextDouble()))
          break
        case 1:
          tags.put("tag.3." + i, BigInteger.valueOf(random.nextLong()))
          break
        default:
          break
      }
    }
    int metricCount = random.nextInt(0, 20)
    for (int i = 0; i < metricCount; ++i) {
      String name = "metric." + i
      Number metric = null
      switch (random.nextInt(4)) {
        case 0:
          metric = random.nextInt()
          break
        case 1:
          metric = random.nextLong()
          break
        case 2:
          metric = random.nextFloat()
          break
        case 3:
          metric = random.nextDouble()
          break
      }
      tags.put(name, metric)
    }

    return new PojoSpan(
      "service-" + random.nextInt(lowCardinality ? 1 : 10),
      "operation-" + random.nextInt(lowCardinality ? 1 : 100),
      UTF8BytesString.create("resource-" + random.nextInt(lowCardinality ? 1 : 100)),
      DDTraceId.from(traceId),
      ID_GENERATION_STRATEGY.generateSpanId(),
      DDSpanId.ZERO,
      TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()),
      random.nextLong(500, 10_000_000),
      random.nextInt(2),
      baggage,
      tags,
      type,
      random.nextBoolean(),
      PrioritySampling.SAMPLER_KEEP,
      200,
      "some-origin")
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
    private final DDTraceId traceId
    private final long spanId
    private final long parentId
    private final long start
    private final long duration
    private final int error
    private final CharSequence type
    private final boolean measured
    private final Metadata metadata
    private short httpStatusCode
    private final int samplingPriority
    private final Map<String, Object> metaStruct = [:]

    PojoSpan(
    String serviceName,
    String operationName,
    CharSequence resourceName,
    DDTraceId traceId,
    long spanId,
    long parentId,
    long start,
    long duration,
    int error,
    Map<String, String> baggage,
    Map<String, Object> tags,
    CharSequence type,
    boolean measured,
    int samplingPriority,
    int statusCode,
    CharSequence origin) {
      this.serviceName = UTF8BytesString.create(serviceName)
      this.operationName = UTF8BytesString.create(operationName)
      this.resourceName = UTF8BytesString.create(resourceName)
      this.traceId = traceId
      this.spanId = spanId
      this.parentId = parentId
      this.start = start
      this.duration = duration
      this.error = error
      this.type = type
      this.measured = measured
      this.samplingPriority = samplingPriority
      this.metadata = new Metadata(Thread.currentThread().getId(),
        UTF8BytesString.create(Thread.currentThread().getName()), TagMap.fromMap(tags), baggage, samplingPriority, measured, topLevel,
        statusCode == 0 ? null : UTF8BytesString.create(Integer.toString(statusCode)), origin, 0,
        ProcessTags.tagsForSerialization)
      this.httpStatusCode = (short) statusCode
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
    DDTraceId getTraceId() {
      return traceId
    }

    @Override
    long getSpanId() {
      return spanId
    }

    @Override
    long getParentId() {
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
      metadata.getTags().remove(tag)
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
    boolean isForceKeep() {
      return false
    }

    @Override
    short getHttpStatusCode() {
      return httpStatusCode
    }

    @Override
    CharSequence getOrigin() {
      return metadata.getOrigin()
    }

    Map<String, String> getBaggage() {
      return metadata.getBaggage()
    }

    Map<String, Object> getTags() {
      return metadata.getTags()
    }

    @Override
    CharSequence getType() {
      return this.type
    }

    @Override
    void processServiceTags() {}

    @Override
    void processTagsAndBaggage(MetadataConsumer consumer) {
      consumer.accept(metadata)
    }

    @Override
    PojoSpan setSamplingPriority(int samplingPriority, int samplingMechanism) {
      return this
    }

    @Override
    PojoSpan setSamplingPriority(int samplingPriority, CharSequence rate, double sampleRate, int samplingMechanism) {
      return this
    }

    @Override
    PojoSpan setSpanSamplingPriority(double rate, int limit) {
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
      return samplingPriority
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
      return samplingPriority != UNSET
    }

    @Override
    Map<String, Object> getMetaStruct() {
      return metaStruct
    }

    @Override
    PojoSpan setMetaStruct(String field, Object value) {
      if (value == null) {
        metaStruct.remove(field)
      } else {
        metaStruct[field] = value
      }
      return this
    }

    @Override
    int getLongRunningVersion() {
      return 0
    }
  }
}
