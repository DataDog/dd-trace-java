package datadog.trace.core.propagation;

import datadog.trace.api.DDId;
import java.util.Map;

/** {@link ExtractedContext} with non-empty Forwarded metadata. */
public final class ForwardedExtractedContext extends ExtractedContext {

  private final String forwarded;
  private final String forwardedProto;
  private final String forwardedHost;
  private final String forwardedIp;
  private final String forwardedPort;

  public ForwardedExtractedContext(
      final DDId traceId,
      final DDId spanId,
      final int samplingPriority,
      final int samplingMechanism,
      final String origin,
      final long endToEndStartTime,
      final String forwarded,
      final String forwardedProto,
      final String forwardedHost,
      final String forwardedIp,
      final String forwardedPort,
      final Map<String, String> baggage,
      final Map<String, String> tags,
      final HttpHeaders httpHeaders) {
    super(
        traceId,
        spanId,
        samplingPriority,
        samplingMechanism,
        origin,
        endToEndStartTime,
        baggage,
        tags,
        httpHeaders);
    this.forwarded = forwarded;
    this.forwardedProto = forwardedProto;
    this.forwardedHost = forwardedHost;
    this.forwardedIp = forwardedIp;
    this.forwardedPort = forwardedPort;
  }

  @Override
  public String getForwarded() {
    return forwarded;
  }

  @Override
  public String getForwardedProto() {
    return forwardedProto;
  }

  @Override
  public String getForwardedHost() {
    return forwardedHost;
  }

  @Override
  public String getForwardedIp() {
    return forwardedIp;
  }

  @Override
  public String getForwardedPort() {
    return forwardedPort;
  }
}
