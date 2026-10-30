package datadog.trace.bootstrap.instrumentation.decorator;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.SpanData;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// registered for the class so the one method-level @WithConfig is undone after that method
@ExtendWith(WithConfigExtension.class)
class PeerAddressTest {
  private final SpanData span = mock(SpanData.class);

  // getByAddress pairs a hostname with an IP without a DNS lookup
  private static InetAddress ipv4() throws Exception {
    return InetAddress.getByAddress("db.example", new byte[] {10, 0, 0, 1});
  }

  private static InetAddress ipv6() throws Exception {
    byte[] ip = new byte[16];
    ip[15] = 1;
    return InetAddress.getByAddress("db6.example", ip);
  }

  @Test
  void resolvedIpv4SetsHostnameAndIpv4() throws Exception {
    PeerAddress.RESOLVED.extract(ipv4(), span);

    verify(span).setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) "db.example");
    verify(span).setTag(KnownTags.PEER_IPV4_ID, (CharSequence) "10.0.0.1");
    verifyNoMoreInteractions(span);
  }

  @Test
  void resolvedIpv6SetsHostnameAndIpv6() throws Exception {
    InetAddress address = ipv6();
    PeerAddress.RESOLVED.extract(address, span);

    verify(span).setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) "db6.example");
    verify(span).setTag(KnownTags.PEER_IPV6_ID, (CharSequence) address.getHostAddress());
    verifyNoMoreInteractions(span);
  }

  @Test
  void unresolvedSkipsHostname() throws Exception {
    PeerAddress.UNRESOLVED.extract(ipv4(), span);

    verify(span).setTag(KnownTags.PEER_IPV4_ID, (CharSequence) "10.0.0.1");
    verifyNoMoreInteractions(span);
  }

  @Test
  @WithConfig(key = "trace.peer.hostname.enabled", value = "false")
  void hostnameDisabledSkipsHostname() throws Exception {
    PeerAddress.RESOLVED.extract(ipv4(), span);

    verify(span).setTag(KnownTags.PEER_IPV4_ID, (CharSequence) "10.0.0.1");
    verifyNoMoreInteractions(span);
  }

  @Test
  void socketAddressAddsPort() throws Exception {
    PeerSocketAddress.INSTANCE.extract(new InetSocketAddress(ipv4(), 5432), span);

    verify(span).setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) "db.example");
    verify(span).setTag(KnownTags.PEER_IPV4_ID, (CharSequence) "10.0.0.1");
    verify(span).setTag(KnownTags.PEER_PORT_ID, 5432);
    verifyNoMoreInteractions(span);
  }

  @Test
  void unresolvedSocketAddressSetsOnlyPort() {
    PeerSocketAddress.INSTANCE.extract(
        InetSocketAddress.createUnresolved("bad.address.local", 999), span);

    verify(span).setTag(KnownTags.PEER_PORT_ID, 999);
    verifyNoMoreInteractions(span);
  }

  @Test
  void unsetPortIsSkipped() throws Exception {
    PeerSocketAddress.INSTANCE.extract(new InetSocketAddress(ipv4(), 0), span);

    verify(span, never()).setTag(eq(KnownTags.PEER_PORT_ID), anyInt());
  }
}
