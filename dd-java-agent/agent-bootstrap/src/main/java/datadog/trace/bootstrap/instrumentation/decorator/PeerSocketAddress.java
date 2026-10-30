package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.SpanData;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Extracts the peer tags of a remote {@link InetSocketAddress}: those of its address (see {@link
 * PeerAddress}), when it has one, and {@code peer.port}, when set.
 */
public final class PeerSocketAddress implements TagExtractor<InetSocketAddress> {
  public static final PeerSocketAddress INSTANCE = new PeerSocketAddress();

  private PeerSocketAddress() {}

  @Override
  public void extract(InetSocketAddress socketAddress, SpanData span) {
    // an unresolved socket address is exactly one with no address
    InetAddress address = socketAddress.getAddress();
    if (address != null) {
      PeerAddress.RESOLVED.extract(address, span);
    }
    int port = socketAddress.getPort();
    if (port > BaseDecorator.UNSET_PORT) {
      span.setTag(KnownTags.PEER_PORT_ID, port);
    }
  }
}
