package datadog.trace.bootstrap.instrumentation.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.tabletest.junit.TableTest;

class ClientIpAddressResolverTest {
  @TableTest({
    "Scenario                      | Header               | Peer     | Expected            ",
    "no addresses                  |                      |          |                     ",
    "public peer fallback          |                      | 1.2.3.4  | 1.2.3.4             ",
    "private peer fallback         |                      | 10.0.0.5 | 10.0.0.5            ",
    "invalid peer                  |                      | invalid  |                     ",
    "invalid header fallback       | invalid              | 1.2.3.4  | 1.2.3.4             ",
    "public header beats peer      | 1.2.3.4              | 8.8.8.8  | 1.2.3.4             ",
    "public header without peer    | 1.2.3.4              |          | 1.2.3.4             ",
    "public peer beats private     | 10.0.0.2             | 1.2.3.4  | 1.2.3.4             ",
    "private header beats private  | 10.0.0.2             | 10.0.0.5 | 10.0.0.2            ",
    "private header without peer   | 10.0.0.2             |          | 10.0.0.2            ",
    "invalid peer preserves header | 10.0.0.2             | invalid  | 10.0.0.2            ",
    "forwarded chain               | '10.0.0.2, 1.2.3.4'  | 8.8.8.8  | 1.2.3.4             ",
    "public IPv6 header            | 2001:4860:4860::8888 | 1.2.3.4  | 2001:4860:4860::8888",
    "public peer beats loopback    | ::1                  | 1.2.3.4  | 1.2.3.4             "
  })
  void resolvesHeadersAndPeer(String header, String peer, String expected)
      throws UnknownHostException {
    assertEquals(
        address(expected), ClientIpAddressResolver.resolve(context(header, null), null, peer));
  }

  @TableTest({
    "Scenario                  | Custom                    | Peer    | Expected",
    "custom overrides standard | 8.8.8.8                   | 1.2.3.4 | 8.8.8.8 ",
    "custom forwarded syntax   | 'for=8.8.8.8;proto=https' | 1.2.3.4 | 8.8.8.8 ",
    "invalid custom uses peer  | invalid                   | 1.2.3.4 | 1.2.3.4 "
  })
  void honorsCustomHeader(String custom, String peer, String expected) throws UnknownHostException {
    assertEquals(
        address(expected), ClientIpAddressResolver.resolve(context("9.9.9.9", custom), null, peer));
  }

  @Test
  void resolvesPeerWithoutExtractedContext() throws UnknownHostException {
    assertEquals(address("1.2.3.4"), ClientIpAddressResolver.resolve(null, null, "1.2.3.4"));
  }

  @TableTest({
    "Scenario | Value                                          | Expected  ",
    "case 1   | '127.0.0.1, 8.8.8.8'                           | '8.8.8.8' ",
    "case 2   | 'for=\"[::1]\", for=8.8.8.8'                   | '8.8.8.8' ",
    "case 3   | 'for=\"8.8.8.8:8888\"'                         | '8.8.8.8' ",
    "case 4   | 'for=\"[::1]\", for=[::ffff:1.1.1.1]:8888'     | '1.1.1.1' ",
    "case 5   | 'for=\"[::1]\", for=\"[::ffff:1.1.1.1]:8888\"' | '1.1.1.1' ",
    "case 6   | '10.0.0.1'                                     | '10.0.0.1'"
  })
  void resolvesCustomHeaders(String value, String expected) throws UnknownHostException {
    assertEquals(address(expected), ClientIpAddressResolver.resolve(context(null, value), null));
  }

  @TableTest({
    "Scenario | Header                | Value                                                              | Expected           ",
    "case 1   | 'x-forwarded-for'     | '2001:0::1'                                                        | '2001::1'          ",
    "case 2   | 'x-forwarded-for'     | '::1, febf::1, fc00::1, fd00::1,2001:0000::1'                      | '2001::1'          ",
    "case 3   | 'x-forwarded-for'     | 'fec0::,feff::ffff,fd00::,fdff::ffff,2001::1'                      | '2001::1'          ",
    "case 4   | 'x-forwarded-for'     | '172.16.0.1'                                                       | '172.16.0.1'       ",
    "case 5   | 'x-forwarded-for'     | '172.16.0.1, 172.31.255.254, 172.32.255.1, 8.8.8.8'                | '172.32.255.1'     ",
    "case 6   | 'x-forwarded-for'     | '169.254.0.1, 127.1.1.1, 10.255.255.254,'                          | '169.254.0.1'      ",
    "case 7   | 'x-forwarded-for'     | '127.1.1.1,, '                                                     | '127.1.1.1'        ",
    "case 8   | 'x-forwarded-for'     | 'bad_value, 1.1.1.1'                                               | '1.1.1.1'          ",
    "case 9   | 'x-real-ip'           | '2.2.2.2'                                                          | '2.2.2.2'          ",
    "case 10  | 'x-real-ip'           | '2.2.2.2, 3.3.3.3'                                                 | '2.2.2.2'          ",
    "case 11  | 'x-real-ip'           | '127.0.0.1'                                                        | '127.0.0.1'        ",
    "case 12  | 'x-real-ip'           | '::ffff:4.4.4.4'                                                   | '4.4.4.4'          ",
    "case 13  | 'x-real-ip'           | '::ffff:127.0.0.1'                                                 | '127.0.0.1'        ",
    "case 14  | 'x-real-ip'           | '42'                                                               | '0.0.0.42'         ",
    "case 15  | 'x-client-ip'         | '2.2.2.2'                                                          | '2.2.2.2'          ",
    "case 16  | 'x-cluster-client-ip' | '2.2.2.2'                                                          | '2.2.2.2'          ",
    "case 17  | 'forwarded-for'       | '::1, 127.0.0.1, 2001::1'                                          | '2001::1'          ",
    "case 18  | 'true-client-ip'      | '8.8.8.8'                                                          | '8.8.8.8'          ",
    "case 19  | 'fastly-client-ip'    | '3.3.3.3'                                                          | '3.3.3.3'          ",
    "case 20  | 'cf-connecting-ip'    | '4.4.4.4'                                                          | '4.4.4.4'          ",
    "case 21  | 'cf-connecting-ipv6'  | '2001::2'                                                          | '2001::2'          ",
    "case 22  | 'forwarded'           | 'for=\"[2001::1]:1111\"'                                           | '2001::1'          ",
    "case 23  | 'forwarded'           | 'fOr=\"[2001::1]:1111\"'                                           | '2001::1'          ",
    "case 24  | 'forwarded'           | 'for=some_host'                                                    |                    ",
    "case 25  | 'forwarded'           | 'for=127.0.0.1, FOR=1.1.1.1'                                       | '1.1.1.1'          ",
    "case 26  | 'forwarded'           | 'for=\"\"foobar\";proto=http,FOR=\"1.1.1.1\"'                      | '1.1.1.1'          ",
    "case 27  | 'forwarded'           | 'for=\"8.8.8.8:2222\",'                                            | '8.8.8.8'          ",
    "case 28  | 'forwarded'           | 'for=\"8.8.8.8'                                                    |                    ",
    "case 29  | 'forwarded'           | 'far=\"8.8.8.8\",for=4.4.4.4;'                                     | '4.4.4.4'          ",
    "case 30  | 'forwarded'           | '   for=127.0.0.1,for= for=,for=;\"for = for=\"\" ,; for=8.8.8.8;' | '8.8.8.8'          ",
    "case 31  | 'forwarded'           | 'for=192.0.2.60;proto=http;by=203.0.113.43'                        | '192.0.2.60'       ",
    "case 32  | 'forwarded'           | 'For=\"[2001:db8:cafe::17]:4711\"'                                 | '2001:db8:cafe::17'",
    "case 33  | 'forwarded'           | 'for=192.0.2.43;proto=https;by=203.0.113.43'                       | '192.0.2.43'       ",
    "case 34  | 'forwarded'           | 'for=\"_gazonk\"'                                                  |                    ",
    "case 35  | 'forwarded'           | 'for=unknown, for=8.8.8.8'                                         | '8.8.8.8'          ",
    "case 36  | 'forwarded'           | 'for=\"[::ffff:192.0.2.128]\";proto=http'                          | '192.0.2.128'      "
  })
  void resolvesStandardHeaders(String header, String value, String expected)
      throws UnknownHostException {
    assertEquals(
        address(expected), ClientIpAddressResolver.resolve(headerContext(header, value), null));
  }

  @Test
  void customHeaderIsExclusive() throws UnknownHostException {
    AgentSpanContext.Extracted context = mock(AgentSpanContext.Extracted.class);
    when(context.getCustomIpHeader()).thenReturn("8.8.8.8");
    assertEquals(address("8.8.8.8"), ClientIpAddressResolver.resolve(context, null));
    verify(context).getCustomIpHeader();
    verifyNoMoreInteractions(context);
  }

  @Test
  void readsStandardHeadersInPriorityOrder() throws UnknownHostException {
    AgentSpanContext.Extracted context = mock(AgentSpanContext.Extracted.class);
    when(context.getXRealIp()).thenReturn("127.0.0.1");
    when(context.getCfConnectingIpv6()).thenReturn("2001::1");
    assertEquals(address("2001::1"), ClientIpAddressResolver.resolve(context, null));
    verifyAllHeaderReads(context);
  }

  @Test
  void publicHeaderIsPreferredAndStopsFurtherReads() throws UnknownHostException {
    AgentSpanContext.Extracted context = mock(AgentSpanContext.Extracted.class);
    when(context.getXRealIp()).thenReturn("127.0.0.1");
    when(context.getXClientIp()).thenReturn("8.8.8.8");
    assertEquals(address("8.8.8.8"), ClientIpAddressResolver.resolve(context, null));
    InOrder order = inOrder(context);
    order.verify(context).getCustomIpHeader();
    order.verify(context).getXForwardedFor();
    order.verify(context).getXRealIp();
    order.verify(context).getTrueClientIp();
    order.verify(context).getXClientIp();
    verifyNoMoreInteractions(context);
  }

  @Test
  void firstPrivateHeaderIsPreservedAfterReadingAllHeaders() throws UnknownHostException {
    AgentSpanContext.Extracted context = mock(AgentSpanContext.Extracted.class);
    when(context.getXForwardedFor()).thenReturn("127.0.0.1");
    when(context.getXRealIp()).thenReturn("127.0.0.2");
    when(context.getXClientIp()).thenReturn("127.0.0.3");
    when(context.getForwarded()).thenReturn("for=127.0.0.4");
    when(context.getXClusterClientIp()).thenReturn("127.0.0.5");
    when(context.getForwardedFor()).thenReturn("127.0.0.6");
    when(context.getTrueClientIp()).thenReturn("127.0.0.9");
    when(context.getFastlyClientIp()).thenReturn("127.0.0.10");
    when(context.getCfConnectingIp()).thenReturn("127.0.0.11");
    when(context.getCfConnectingIpv6()).thenReturn("::1");
    assertEquals(address("127.0.0.1"), ClientIpAddressResolver.resolve(context, null));
    verifyAllHeaderReads(context);
  }

  @Test
  void unexpectedHeaderReadFailureDoesNotEscape() {
    AgentSpanContext.Extracted context = mock(AgentSpanContext.Extracted.class);
    when(context.getCustomIpHeader()).thenThrow(new IllegalStateException("unreadable carrier"));
    assertNull(ClientIpAddressResolver.resolve(context, null));
  }

  @Test
  void nullContextHasNoHeaderAddress() {
    assertNull(ClientIpAddressResolver.resolve(null, null));
  }

  private static void verifyAllHeaderReads(AgentSpanContext.Extracted context) {
    InOrder order = inOrder(context);
    order.verify(context).getCustomIpHeader();
    order.verify(context).getXForwardedFor();
    order.verify(context).getXRealIp();
    order.verify(context).getTrueClientIp();
    order.verify(context).getXClientIp();
    order.verify(context).getForwarded();
    order.verify(context).getForwardedFor();
    order.verify(context).getXClusterClientIp();
    order.verify(context).getFastlyClientIp();
    order.verify(context).getCfConnectingIp();
    order.verify(context).getCfConnectingIpv6();
    verifyNoMoreInteractions(context);
  }

  private static TagContext headerContext(String name, String value) {
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    switch (name) {
      case "x-forwarded-for":
        headers.xForwardedFor = value;
        break;
      case "x-real-ip":
        headers.xRealIp = value;
        break;
      case "true-client-ip":
        headers.trueClientIp = value;
        break;
      case "x-client-ip":
        headers.xClientIp = value;
        break;
      case "forwarded":
        headers.forwarded = value;
        break;
      case "forwarded-for":
        headers.forwardedFor = value;
        break;
      case "x-cluster-client-ip":
        headers.xClusterClientIp = value;
        break;
      case "fastly-client-ip":
        headers.fastlyClientIp = value;
        break;
      case "cf-connecting-ip":
        headers.cfConnectingIp = value;
        break;
      case "cf-connecting-ipv6":
        headers.cfConnectingIpv6 = value;
        break;
      default:
        throw new IllegalArgumentException(name);
    }
    return context(headers);
  }

  private static InetAddress address(String ip) throws UnknownHostException {
    return ip == null ? null : InetAddress.getByName(ip);
  }

  private static TagContext context(String forwarded, String custom) {
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedFor = forwarded;
    headers.customIpHeader = custom;
    return context(headers);
  }

  private static TagContext context(TagContext.HttpHeaders headers) {
    return new TagContext(
        null,
        null,
        headers,
        null,
        PrioritySampling.UNSET,
        null,
        TracePropagationStyle.NONE,
        DDTraceId.ZERO);
  }
}
