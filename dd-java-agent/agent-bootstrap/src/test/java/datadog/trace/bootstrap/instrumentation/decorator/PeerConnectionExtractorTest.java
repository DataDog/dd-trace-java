package datadog.trace.bootstrap.instrumentation.decorator;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PeerConnectionExtractor#extract}. Covers the deterministic IP-family and
 * port routing; the config/resolver-dependent {@code PEER_HOSTNAME} tag is intentionally not
 * asserted (that behaviour belongs to the host-name resolver, and peer-hostname is on by default,
 * so it would couple this test to reverse-DNS). Null source handling is the responsibility of
 * {@code AgentSpan.setTags} (the extractor's sole entry point), not the extractor.
 *
 * <p>Literal IPs are used so no DNS lookup happens.
 */
class PeerConnectionExtractorTest {

  @Test
  void ipv4AddressSetsIpv4TagAndPort() throws UnknownHostException {
    final AgentSpan span = mock(AgentSpan.class);
    final InetAddress addr = InetAddress.getByName("1.2.3.4"); // literal -> no DNS

    PeerConnectionExtractor.INSTANCE.extract(new InetSocketAddress(addr, 8080), span);

    verify(span).setTag(Tags.PEER_HOST_IPV4, addr.getHostAddress());
    verify(span).setTag(Tags.PEER_PORT, 8080);
    verify(span, never()).setTag(eq(Tags.PEER_HOST_IPV6), anyString());
  }

  @Test
  void ipv6AddressSetsIpv6TagAndPort() throws UnknownHostException {
    final AgentSpan span = mock(AgentSpan.class);
    final InetAddress addr = InetAddress.getByName("2001:db8::1"); // literal -> no DNS

    PeerConnectionExtractor.INSTANCE.extract(new InetSocketAddress(addr, 9090), span);

    verify(span).setTag(Tags.PEER_HOST_IPV6, addr.getHostAddress());
    verify(span).setTag(Tags.PEER_PORT, 9090);
    verify(span, never()).setTag(eq(Tags.PEER_HOST_IPV4), anyString());
  }

  @Test
  void unresolvedAddressSetsOnlyPort() {
    final AgentSpan span = mock(AgentSpan.class);

    PeerConnectionExtractor.INSTANCE.extract(
        InetSocketAddress.createUnresolved("no.dns.local", 999), span);

    verify(span).setTag(Tags.PEER_PORT, 999);
    verify(span, never()).setTag(eq(Tags.PEER_HOST_IPV4), anyString());
    verify(span, never()).setTag(eq(Tags.PEER_HOST_IPV6), anyString());
  }
}
