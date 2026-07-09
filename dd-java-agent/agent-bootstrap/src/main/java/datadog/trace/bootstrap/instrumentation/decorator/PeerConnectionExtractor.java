package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver.hostName;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Named singleton {@link TagExtractor} for peer-connection tags (hostname / IPv4 / IPv6 / port)
 * from a remote {@link InetSocketAddress}.
 *
 * <p>Promoted from an inline lambda in {@link BaseDecorator} to a named class so it can be:
 * referenced by name at call sites ({@code span.setTags(addr, PeerConnectionExtractor.INSTANCE)}),
 * <em>composed</em> with other extractors (the axis the Decorator inheritance chain lacked), and
 * given a home for the pure statics and the {@code hostName} resolver cache it consults.
 * Non-capturing and stateless — the single {@link #INSTANCE} is effectively a static function
 * object, so a monomorphic call site inlines it away.
 */
public final class PeerConnectionExtractor implements TagExtractor<InetSocketAddress> {
  public static final PeerConnectionExtractor INSTANCE = new PeerConnectionExtractor();

  private static final int UNSET_PORT = 0;

  private PeerConnectionExtractor() {}

  @Override
  public void extract(final InetSocketAddress remoteConnection, final AgentSpan span) {
    // Non-null guaranteed by AgentSpan.setTags (the sole entry point); extractors skip
    // null-handling.
    setPeerAddress(span, remoteConnection.getAddress(), !remoteConnection.isUnresolved());
    final int port = remoteConnection.getPort();
    if (port > UNSET_PORT) {
      span.setTag(Tags.PEER_PORT, port);
    }
  }

  static void setPeerAddress(
      final AgentSpan span, final InetAddress remoteAddress, final boolean resolved) {
    if (remoteAddress != null) {
      String ip = remoteAddress.getHostAddress();
      if (resolved && Config.get().isPeerHostNameEnabled()) {
        span.setTag(Tags.PEER_HOSTNAME, hostName(remoteAddress, ip));
      }
      if (remoteAddress instanceof Inet4Address) {
        span.setTag(Tags.PEER_HOST_IPV4, ip);
      } else if (remoteAddress instanceof Inet6Address) {
        span.setTag(Tags.PEER_HOST_IPV6, ip);
      }
    }
  }
}
