package datadog.trace.core.propagation;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Map;

/**
 * Propagated data resulting from calling tracer.extract with header data from an incoming request.
 */
public class ExtractedContext extends TagContext {
  private final DDId traceId;
  private final DDId spanId;
  private final int samplingPriority;
  private final int samplingMechanism;
  private final long endToEndStartTime;
  private final Map<String, String> baggage;

  public ExtractedContext(
      final DDId traceId,
      final DDId spanId,
      final int samplingPriority,
      final int samplingMechanism,
      final String origin,
      final long endToEndStartTime,
      final Map<String, String> baggage,
      final Map<String, String> tags,
      final HttpHeaders httpHeaders) {
    super(origin, tags, httpHeaders);
    this.traceId = traceId;
    this.spanId = spanId;
    this.samplingPriority = samplingPriority;
    this.samplingMechanism = samplingMechanism;
    this.endToEndStartTime = endToEndStartTime;
    this.baggage = baggage;
  }

  public ExtractedContext(
      final DDId traceId,
      final DDId spanId,
      final int samplingPriority,
      final int samplingMechanism,
      final String origin,
      final long endToEndStartTime,
      final Map<String, String> baggage,
      final Map<String, String> tags) {
    super(origin, tags, HttpHeaders.NO_HEADERS);
    this.traceId = traceId;
    this.spanId = spanId;
    this.samplingPriority = samplingPriority;
    this.samplingMechanism = samplingMechanism;
    this.endToEndStartTime = endToEndStartTime;
    this.baggage = baggage;
  }

  @Override
  public final Iterable<Map.Entry<String, String>> baggageItems() {
    return baggage.entrySet();
  }

  @Override
  public final DDId getTraceId() {
    return traceId;
  }

  @Override
  public final DDId getSpanId() {
    return spanId;
  }

  public final int getSamplingPriority() {
    return samplingPriority;
  }

  public final int getSamplingMechanism() {
    return samplingMechanism;
  }

  public final long getEndToEndStartTime() {
    return endToEndStartTime;
  }

  public final Map<String, String> getBaggage() {
    return baggage;
  }
}
