package datadog.trace.core.propagation;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Map;

/**
 * Propagated data resulting from calling tracer.extract with header data from an incoming request.
 */
public class ExtractedContext extends TagContext {
  private final DDTraceId traceId;
  private final DDSpanId spanId;
  private final int samplingPriority;
  private final long endToEndStartTime;
  private final Map<String, String> baggage;
  private final DatadogTags datadogTags;

  public ExtractedContext(
      final DDTraceId traceId,
      final DDSpanId spanId,
      final int samplingPriority,
      final String origin,
      final long endToEndStartTime,
      final Map<String, String> baggage,
      final Map<String, String> tags,
      final HttpHeaders httpHeaders,
      final DatadogTags datadogTags) {
    super(origin, tags, httpHeaders);
    this.traceId = traceId;
    this.spanId = spanId;
    this.samplingPriority = samplingPriority;
    this.endToEndStartTime = endToEndStartTime;
    this.baggage = baggage;
    this.datadogTags = datadogTags;
  }

  @Override
  public final Iterable<Map.Entry<String, String>> baggageItems() {
    return baggage.entrySet();
  }

  @Override
  public final DDTraceId getTraceId() {
    return traceId;
  }

  @Override
  public final DDSpanId getSpanId() {
    return spanId;
  }

  public final int getSamplingPriority() {
    return samplingPriority;
  }

  public final long getEndToEndStartTime() {
    return endToEndStartTime;
  }

  public final Map<String, String> getBaggage() {
    return baggage;
  }

  public DatadogTags getDatadogTags() {
    return datadogTags;
  }
}
