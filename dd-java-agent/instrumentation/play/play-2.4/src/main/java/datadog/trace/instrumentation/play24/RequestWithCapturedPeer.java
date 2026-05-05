package datadog.trace.instrumentation.play24;

import play.api.mvc.Request;
import play.api.mvc.WrappedRequest;

/**
 * Wraps a {@link Request} so that {@link #remoteAddress()} returns a previously captured peer IP
 * (typically read from the upstream akka/netty span, which obtained it from the actual TCP socket
 * peer). This bypasses Play's X-Forwarded-For-resolved {@code remoteAddress} when tagging peer
 * information. See APPSEC-62562.
 */
public class RequestWithCapturedPeer extends WrappedRequest<Object> {
  private final String capturedPeerAddress;

  @SuppressWarnings({"unchecked", "rawtypes"})
  public RequestWithCapturedPeer(final Request<?> request, final String capturedPeerAddress) {
    super((Request) request);
    this.capturedPeerAddress = capturedPeerAddress;
  }

  @Override
  public String remoteAddress() {
    return capturedPeerAddress;
  }
}
