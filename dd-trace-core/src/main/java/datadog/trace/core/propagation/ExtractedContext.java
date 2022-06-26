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
  private final HttpHeaders httpHeaders;

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
    super(origin, tags);
    this.traceId = traceId;
    this.spanId = spanId;
    this.samplingPriority = samplingPriority;
    this.samplingMechanism = samplingMechanism;
    this.endToEndStartTime = endToEndStartTime;
    this.baggage = baggage;
    this.httpHeaders = httpHeaders;
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
    this(
        traceId,
        spanId,
        samplingPriority,
        samplingMechanism,
        origin,
        endToEndStartTime,
        baggage,
        tags,
        null);
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

  @Override
  public String getForwardedFor() {
    return httpHeaders.forwardedFor;
  }

  @Override
  public String getXForwarded() {
    return httpHeaders.xForwarded;
  }

  @Override
  public String getXForwardedFor() {
    return httpHeaders.xForwardedFor;
  }

  @Override
  public String getXClusterClientIp() {
    return httpHeaders.xClusterClientIp;
  }

  @Override
  public String getXRealIp() {
    return httpHeaders.xRealIp;
  }

  @Override
  public String getClientIp() {
    return httpHeaders.clientIp;
  }

  @Override
  public String getUserAgent() {
    return httpHeaders.userAgent;
  }

  @Override
  public String getVia() {
    return httpHeaders.via;
  }

  @Override
  public String getTrueClientIp() {
    return httpHeaders.trueClientIp;
  }

  @Override
  public String getCustomIpHeader() {
    return httpHeaders.customIpHeader;
  }
}
