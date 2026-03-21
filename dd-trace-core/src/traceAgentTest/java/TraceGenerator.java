import static datadog.trace.api.sampling.PrioritySampling.UNSET;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class TraceGenerator {

  public static List<List<CoreSpan>> generateRandomTraces(int howMany, boolean lowCardinality) {
    List<List<CoreSpan>> traces = new ArrayList<>(howMany);
    for (int i = 0; i < howMany; ++i) {
      int traceSize = ThreadLocalRandom.current().nextInt(2, 20);
      traces.add(generateRandomTrace(traceSize, lowCardinality));
    }
    return traces;
  }

  private static List<CoreSpan> generateRandomTrace(int size, boolean lowCardinality) {
    List<CoreSpan> trace = new ArrayList<>(size);
    long traceId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    for (int i = 0; i < size; ++i) {
      trace.add(randomSpan(traceId, lowCardinality));
    }
    return trace;
  }

  private static final IdGenerationStrategy ID_GENERATION_STRATEGY =
      IdGenerationStrategy.fromName("RANDOM");

  private static CoreSpan randomSpan(long traceId, boolean lowCardinality) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    Map<String, String> baggage = new HashMap<>();
    if (random.nextBoolean()) {
      baggage.put("baggage-key", lowCardinality ? "x" : randomString(100));
      if (random.nextBoolean()) {
        baggage.put("tag.1", "bar");
        baggage.put("tag.2", "qux");
      }
    }
    Map<String, Object> tags = new HashMap<>();
    int tagCount = random.nextInt(0, 20);
    for (int i = 0; i < tagCount; ++i) {
      tags.put("tag." + i, random.nextBoolean() ? "foo" : randomString(2000));
      tags.put("tag.1." + i, lowCardinality ? "y" : UUID.randomUUID());
      switch (random.nextInt(8)) {
        case 0:
          tags.put("tag.3." + i, BigDecimal.valueOf(random.nextDouble()));
          break;
        case 1:
          tags.put("tag.3." + i, BigInteger.valueOf(random.nextLong()));
          break;
        default:
          break;
      }
    }
    int metricCount = random.nextInt(0, 20);
    for (int i = 0; i < metricCount; ++i) {
      String name = "metric." + i;
      Number metric = null;
      switch (random.nextInt(4)) {
        case 0:
          metric = random.nextInt();
          break;
        case 1:
          metric = random.nextLong();
          break;
        case 2:
          metric = random.nextFloat();
          break;
        case 3:
          metric = random.nextDouble();
          break;
      }
      tags.put(name, metric);
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
        "type-" + random.nextInt(lowCardinality ? 1 : 100),
        random.nextBoolean());
  }

  private static String randomString(int maxLength) {
    char[] chars = new char[ThreadLocalRandom.current().nextInt(maxLength)];
    for (int i = 0; i < chars.length; ++i) {
      char next = (char) ThreadLocalRandom.current().nextInt((int) Character.MAX_VALUE);
      if (Character.isSurrogate(next)) {
        if (i < chars.length - 1) {
          chars[i++] = '\uD801';
          chars[i] = '\uDC01';
        } else {
          chars[i] = 'a';
        }
      } else {
        chars[i] = next;
      }
    }
    return new String(chars);
  }

  public static class PojoSpan implements CoreSpan<PojoSpan> {

    private final CharSequence serviceName;
    private final CharSequence operationName;
    private final CharSequence resourceName;
    private final DDTraceId traceId;
    private final long spanId;
    private final long parentId;
    private final long start;
    private final long duration;
    private final int error;
    private final String type;
    private final boolean measured;
    private final Metadata metadata;

    public PojoSpan(
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
        String type,
        boolean measured) {
      this.serviceName = UTF8BytesString.create(serviceName);
      this.operationName = UTF8BytesString.create(operationName);
      this.resourceName = UTF8BytesString.create(resourceName);
      this.traceId = traceId;
      this.spanId = spanId;
      this.parentId = parentId;
      this.start = start;
      this.duration = duration;
      this.error = error;
      this.type = type;
      this.measured = measured;
      this.metadata =
          new Metadata(
              Thread.currentThread().getId(),
              UTF8BytesString.create(Thread.currentThread().getName()),
              TagMap.fromMap(tags),
              baggage,
              UNSET,
              measured,
              false,
              null,
              null,
              0,
              ProcessTags.getTagsForSerialization());
    }

    @Override
    public PojoSpan getLocalRootSpan() {
      return this;
    }

    @Override
    public String getServiceName() {
      return serviceName.toString();
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
      return traceId;
    }

    @Override
    public long getSpanId() {
      return spanId;
    }

    @Override
    public long getParentId() {
      return parentId;
    }

    @Override
    public long getStartTime() {
      return start;
    }

    @Override
    public long getDurationNano() {
      return duration;
    }

    @Override
    public int getError() {
      return error;
    }

    @Override
    public short getHttpStatusCode() {
      return 0;
    }

    @Override
    public CharSequence getOrigin() {
      return null;
    }

    @Override
    public PojoSpan setMeasured(boolean measured) {
      return this;
    }

    @Override
    public PojoSpan setErrorMessage(String errorMessage) {
      return this;
    }

    @Override
    public PojoSpan addThrowable(Throwable error) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, String value) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, boolean value) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, int value) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, long value) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, double value) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, Number value) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, CharSequence value) {
      return this;
    }

    @Override
    public PojoSpan setTag(String tag, Object value) {
      return this;
    }

    @Override
    public PojoSpan removeTag(String tag) {
      return this;
    }

    @Override
    public boolean isMeasured() {
      return measured;
    }

    @Override
    public boolean isTopLevel() {
      return false;
    }

    @Override
    public boolean isForceKeep() {
      return false;
    }

    public Map<String, String> getBaggage() {
      return metadata.getBaggage();
    }

    public TagMap getTags() {
      return metadata.getTags();
    }

    @Override
    public CharSequence getType() {
      return type;
    }

    @Override
    public void processServiceTags() {}

    @Override
    public void processTagsAndBaggage(MetadataConsumer consumer) {
      consumer.accept(metadata);
    }

    @Override
    public PojoSpan setSamplingPriority(int samplingPriority, int samplingMechanism) {
      return this;
    }

    @Override
    public PojoSpan setSamplingPriority(
        int samplingPriority, CharSequence rate, double sampleRate, int samplingMechanism) {
      return this;
    }

    @Override
    public PojoSpan setSpanSamplingPriority(double rate, int limit) {
      return this;
    }

    @Override
    public PojoSpan setMetric(CharSequence name, int value) {
      return this;
    }

    @Override
    public PojoSpan setMetric(CharSequence name, long value) {
      return this;
    }

    @Override
    public PojoSpan setMetric(CharSequence name, float value) {
      return this;
    }

    @Override
    public PojoSpan setMetric(CharSequence name, double value) {
      return this;
    }

    @Override
    public PojoSpan setFlag(CharSequence name, boolean value) {
      return this;
    }

    @Override
    public int samplingPriority() {
      return UNSET;
    }

    @Override
    public <U> U getTag(CharSequence name, U defaultValue) {
      U value = getTag(name);
      return null == value ? defaultValue : value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <U> U getTag(CharSequence name) {
      // replicate logic here because DDSpanContext has to pretend some of its
      // fields are elements of a map for backward compatibility reasons
      String tag = String.valueOf(name);
      Object value;
      if (DDTags.THREAD_ID.equals(tag)) {
        value = metadata.getThreadId();
      } else if (DDTags.THREAD_NAME.equals(tag)) {
        value = metadata.getThreadName();
      } else {
        value = getTags().get(tag);
      }
      return (U) value;
    }

    @Override
    public boolean hasSamplingPriority() {
      return false;
    }

    @Override
    public Map<String, Object> getMetaStruct() {
      return Collections.emptyMap();
    }

    @Override
    public PojoSpan setMetaStruct(String field, Object value) {
      return this;
    }

    @Override
    public int getLongRunningVersion() {
      return 0;
    }
  }
}
