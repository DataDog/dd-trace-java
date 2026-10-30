package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver.hostName;

import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.SpanData;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Extracts the peer tags of a remote {@link InetAddress}: its IP, as {@code peer.ipv4} or {@code
 * peer.ipv6}, and, when the address was resolved and peer hostnames are enabled, {@code
 * peer.hostname}.
 */
public final class PeerAddress implements TagExtractor<InetAddress> {
  /** For an address that was resolved from a hostname. */
  public static final PeerAddress RESOLVED = new PeerAddress(true);

  /** For an address that wasn't resolved: no {@code peer.hostname}. */
  public static final PeerAddress UNRESOLVED = new PeerAddress(false);

  private final boolean resolved;

  private PeerAddress(boolean resolved) {
    this.resolved = resolved;
  }

  @Override
  public void extract(InetAddress address, SpanData span) {
    String ip = address.getHostAddress();
    if (resolved && Config.get().isPeerHostNameEnabled()) {
      span.setTag(KnownTags.PEER_HOSTNAME_ID, hostName(address, ip));
    }
    if (address instanceof Inet4Address) {
      span.setTag(KnownTags.PEER_IPV4_ID, ip);
    } else if (address instanceof Inet6Address) {
      span.setTag(KnownTags.PEER_IPV6_ID, ip);
    }
  }
}
