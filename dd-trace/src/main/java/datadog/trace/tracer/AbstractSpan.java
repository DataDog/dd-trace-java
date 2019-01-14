package datadog.trace.tracer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import datadog.trace.api.DDTags;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract span. The main purpose of this class is to define Span's JSON rendering config and provde funtionality that is common for different span implementations.
 *
 * <p>This class is not thread safe.</p>
 */
// Disable autodetection of fields and accessors
@JsonAutoDetect(
  fieldVisibility = Visibility.NONE,
  setterVisibility = Visibility.NONE,
  getterVisibility = Visibility.NONE,
  isGetterVisibility = Visibility.NONE,
  creatorVisibility = Visibility.NONE)
abstract class AbstractSpan implements Span {

  private final TraceInternal trace;

  private final SpanContext context;
  private final Timestamp startTimestamp;

  private Long duration = null;

  private String service;
  private String resource;
  private String type;
  private String name;
  private boolean errored = false;

  private final Map<String, Object> meta = new HashMap<>();

  AbstractSpan(final TraceInternal trace, final SpanContext context, final Timestamp startTimestamp) {
    this.trace = trace;
    this.context = context;

    if (startTimestamp == null) {
      throw new TraceException(String.format("Cannot create span without timestamp: %s", trace));
    }
    this.startTimestamp = startTimestamp;
  }

  protected AbstractSpan(final TraceInternal trace, final Span span) {
    this.trace = trace;

    context = span.getContext();
    startTimestamp = span.getStartTimestamp();

    duration = span.getDuration();

    service = span.getService();
    resource = span.getResource();
    type = span.getType();
    name = span.getName();
    errored = span.isErrored();

    // TODO: is there a good way to avoid copying map here? Alternative might be to not copy spans if there are no interceptors
    meta.putAll(span.getMeta());
  }

  @Override
  public TraceInternal getTrace() {
    return trace;
  }

  @Override
  public SpanContext getContext() {
    return context;
  }

  @JsonGetter("trace_id")
  @JsonSerialize(using = UInt64IDStringSerializer.class)
  protected String getTraceId() {
    return context.getTraceId();
  }

  @JsonGetter("span_id")
  @JsonSerialize(using = UInt64IDStringSerializer.class)
  protected String getSpanId() {
    return context.getSpanId();
  }

  @JsonGetter("parent_id")
  @JsonSerialize(using = UInt64IDStringSerializer.class)
  protected String getParentId() {
    return context.getParentId();
  }

  @Override
  @JsonGetter("start")
  public Timestamp getStartTimestamp() {
    return startTimestamp;
  }

  @Override
  @JsonGetter("duration")
  public Long getDuration() {
    return duration;
  }

  protected void setDuration(final long duration) {
    this.duration = duration;
  }

  @Override
  public boolean isFinished() {
    return duration != null;
  }

  @Override
  @JsonGetter("service")
  public String getService() {
    return service;
  }

  @Override
  public void setService(final String service) {
    this.service = service;
  }

  @Override
  @JsonGetter("resource")
  public String getResource() {
    return resource;
  }

  @Override
  public void setResource(final String resource) {
    this.resource = resource;
  }

  @Override
  @JsonGetter("type")
  public String getType() {
    return type;
  }

  @Override
  public void setType(final String type) {
    this.type = type;
  }

  @Override
  @JsonGetter("name")
  public String getName() {
    return name;
  }

  @Override
  public void setName(final String name) {
    this.name = name;
  }

  @Override
  @JsonGetter("error")
  @JsonFormat(shape = JsonFormat.Shape.NUMBER)
  public boolean isErrored() {
    return errored;
  }

  @Override
  public void attachThrowable(final Throwable throwable) {
    setErrored(true);

    setMeta(DDTags.ERROR_MSG, throwable.getMessage());
    setMeta(DDTags.ERROR_TYPE, throwable.getClass().getName());

    final StringWriter errorString = new StringWriter();
    throwable.printStackTrace(new PrintWriter(errorString));
    setMeta(DDTags.ERROR_STACK, errorString.toString());
  }

  @Override
  public void setErrored(final boolean errored) {
    this.errored = errored;
  }

  @Override
  public Map<String, Object> getMeta() {
    return Collections.unmodifiableMap(meta);
  }

  @JsonGetter("meta")
  protected Map<String, String> getMetaJsonified() {
    final Map<String, String> result = new HashMap<>(meta.size());
    for (final Map.Entry<String, Object> entry : meta.entrySet()) {
      result.put(entry.getKey(), String.valueOf(entry.getValue()));
    }
    return result;
  }

  @Override
  public Object getMeta(final String key) {
    return meta.get(key);
  }

  protected void setMeta(final String key, final Object value) {
    if (value == null) {
      meta.remove(key);
    } else {
      meta.put(key, value);
    }
  }

  @Override
  public void setMeta(final String key, final String value) {
    setMeta(key, (Object) value);
  }

  @Override
  public void setMeta(final String key, final Boolean value) {
    setMeta(key, (Object) value);
  }

  @Override
  public void setMeta(final String key, final Number value) {
    setMeta(key, (Object) value);
  }

  /** Helper to serialize string value as 64 bit unsigned integer */
  private static class UInt64IDStringSerializer extends StdSerializer<String> {

    public UInt64IDStringSerializer() {
      super(String.class);
    }

    @Override
    public void serialize(
      final String value, final JsonGenerator jsonGenerator, final SerializerProvider provider)
      throws IOException {
      jsonGenerator.writeNumber(new BigInteger(value));
    }
  }
}
