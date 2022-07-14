package datadog.trace.bootstrap.instrumentation.api;

import java.util.Map;

/** {@link TagContext} with non-empty Forwarded metadata. */
public final class ForwardedTagContext extends TagContext {

  private final String forwarded;
  private final String forwardedProto;
  private final String forwardedHost;
  private final String forwardedIp;
  private final String forwardedPort;

  public ForwardedTagContext(
      final String origin,
      final String forwarded,
      final String forwardedProto,
      final String forwardedHost,
      final String forwardedIp,
      final String forwardedPort,
      final Map<String, String> tags,
      final HttpHeaders httpHeaders) {
    super(origin, tags, httpHeaders);
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
