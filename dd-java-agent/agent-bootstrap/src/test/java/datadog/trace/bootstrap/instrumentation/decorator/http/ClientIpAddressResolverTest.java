package datadog.trace.bootstrap.instrumentation.decorator.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext.Extracted;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.tabletest.junit.TableTest;

class ClientIpAddressResolverTest {

  private static final Map<String, Function<Extracted, String>> HEADER_GETTERS = new HashMap<>();

  static {
    HEADER_GETTERS.put("x-forwarded-for", Extracted::getXForwardedFor);
    HEADER_GETTERS.put("x-real-ip", Extracted::getXRealIp);
    HEADER_GETTERS.put("x-client-ip", Extracted::getXClientIp);
    HEADER_GETTERS.put("x-cluster-client-ip", Extracted::getXClusterClientIp);
    HEADER_GETTERS.put("forwarded-for", Extracted::getForwardedFor);
    HEADER_GETTERS.put("true-client-ip", Extracted::getTrueClientIp);
    HEADER_GETTERS.put("fastly-client-ip", Extracted::getFastlyClientIp);
    HEADER_GETTERS.put("cf-connecting-ip", Extracted::getCfConnectingIp);
    HEADER_GETTERS.put("cf-connecting-ipv6", Extracted::getCfConnectingIpv6);
    HEADER_GETTERS.put("forwarded", Extracted::getForwarded);
  }

  @TableTest({
    "scenario                       | headerValue                                    | result  ",
    "plain ip list                  | '127.0.0.1, 8.8.8.8'                           | 8.8.8.8 ",
    "forwarded list                 | 'for=\"[::1]\", for=8.8.8.8'                   | 8.8.8.8 ",
    "forwarded with port            | 'for=\"8.8.8.8:8888\"'                         | 8.8.8.8 ",
    // though we allow this, the correct form is the next
    "unquoted mapped ipv6 with port | 'for=\"[::1]\", for=[::ffff:1.1.1.1]:8888'     | 1.1.1.1 ",
    "quoted mapped ipv6 with port   | 'for=\"[::1]\", for=\"[::ffff:1.1.1.1]:8888\"' | 1.1.1.1 ",
    "private ip                     | '10.0.0.1'                                     | 10.0.0.1"
  })
  void testWithCustomHeader(String headerValue, String result) throws UnknownHostException {
    MutableSpan span = mock(MutableSpan.class);
    Extracted context = mock(Extracted.class);
    when(context.getCustomIpHeader()).thenReturn(headerValue);

    InetAddress expected = result != null ? InetAddress.getByName(result) : null;
    assertEquals(expected, ClientIpAddressResolver.resolve(context, span));
    verify(context).getCustomIpHeader();
  }

  @TableTest({
    "scenario                    | header              | headerValue                                                        | result           ",
    "xff ipv6                    | x-forwarded-for     | '2001:0::1'                                                        | 2001::1          ",
    "xff ipv6 skips private      | x-forwarded-for     | '::1, febf::1, fc00::1, fd00::1,2001:0000::1'                      | 2001::1          ",
    "xff ipv6 private ranges     | x-forwarded-for     | 'fec0::,feff::ffff,fd00::,fdff::ffff,2001::1'                      | 2001::1          ",
    "xff private ipv4            | x-forwarded-for     | '172.16.0.1'                                                       | 172.16.0.1       ",
    "xff 172 range boundaries    | x-forwarded-for     | '172.16.0.1, 172.31.255.254, 172.32.255.1, 8.8.8.8'                | 172.32.255.1     ",
    "xff all private             | x-forwarded-for     | '169.254.0.1, 127.1.1.1, 10.255.255.254,'                          | 169.254.0.1      ",
    "xff empty entries           | x-forwarded-for     | '127.1.1.1,, '                                                     | 127.1.1.1        ",
    "xff bad value               | x-forwarded-for     | 'bad_value, 1.1.1.1'                                               | 1.1.1.1          ",
    "x-real-ip                   | x-real-ip           | '2.2.2.2'                                                          | 2.2.2.2          ",
    "x-real-ip list              | x-real-ip           | '2.2.2.2, 3.3.3.3'                                                 | 2.2.2.2          ",
    "x-real-ip loopback          | x-real-ip           | '127.0.0.1'                                                        | 127.0.0.1        ",
    "x-real-ip mapped ipv4       | x-real-ip           | '::ffff:4.4.4.4'                                                   | 4.4.4.4          ",
    "x-real-ip mapped loopback   | x-real-ip           | '::ffff:127.0.0.1'                                                 | 127.0.0.1        ",
    "x-real-ip integer           | x-real-ip           | '42'                                                               | 0.0.0.42         ",
    "x-client-ip                 | x-client-ip         | '2.2.2.2'                                                          | 2.2.2.2          ",
    "x-cluster-client-ip         | x-cluster-client-ip | '2.2.2.2'                                                          | 2.2.2.2          ",
    "forwarded-for               | forwarded-for       | '::1, 127.0.0.1, 2001::1'                                          | 2001::1          ",
    "true-client-ip              | true-client-ip      | '8.8.8.8'                                                          | 8.8.8.8          ",
    "fastly-client-ip            | fastly-client-ip    | '3.3.3.3'                                                          | 3.3.3.3          ",
    "cf-connecting-ip            | cf-connecting-ip    | '4.4.4.4'                                                          | 4.4.4.4          ",
    "cf-connecting-ipv6          | cf-connecting-ipv6  | '2001::2'                                                          | 2001::2          ",
    "forwarded ipv6 with port    | forwarded           | 'for=\"[2001::1]:1111\"'                                           | 2001::1          ",
    "forwarded mixed case        | forwarded           | 'fOr=\"[2001::1]:1111\"'                                           | 2001::1          ",
    "forwarded host name         | forwarded           | 'for=some_host'                                                    |                  ",
    "forwarded upper case        | forwarded           | 'for=127.0.0.1, FOR=1.1.1.1'                                       | 1.1.1.1          ",
    "forwarded escaped quote     | forwarded           | 'for=\"\"foobar\";proto=http,FOR=\"1.1.1.1\"'                      | 1.1.1.1          ",
    "forwarded trailing comma    | forwarded           | 'for=\"8.8.8.8:2222\",'                                            | 8.8.8.8          ",
    // quote not closed
    "forwarded unclosed quote    | forwarded           | 'for=\"8.8.8.8'                                                    |                  ",
    "forwarded other key         | forwarded           | 'far=\"8.8.8.8\",for=4.4.4.4;'                                     | 4.4.4.4          ",
    "forwarded garbage           | forwarded           | '   for=127.0.0.1,for= for=,for=;\"for = for=\"\" ,; for=8.8.8.8;' | 8.8.8.8          ",
    "forwarded with proto and by | forwarded           | 'for=192.0.2.60;proto=http;by=203.0.113.43'                        | 192.0.2.60       ",
    "forwarded rfc ipv6          | forwarded           | 'For=\"[2001:db8:cafe::17]:4711\"'                                 | 2001:db8:cafe::17",
    "forwarded with https        | forwarded           | 'for=192.0.2.43;proto=https;by=203.0.113.43'                       | 192.0.2.43       ",
    "forwarded obfuscated        | forwarded           | 'for=\"_gazonk\"'                                                  |                  ",
    "forwarded unknown           | forwarded           | 'for=unknown, for=8.8.8.8'                                         | 8.8.8.8          ",
    "forwarded mapped ipv4       | forwarded           | 'for=\"[::ffff:192.0.2.128]\";proto=http'                          | 192.0.2.128      "
  })
  void testWithStandardHeader(String header, String headerValue, String result)
      throws UnknownHostException {
    MutableSpan span = mock(MutableSpan.class);
    Extracted context = mock(Extracted.class);
    Function<Extracted, String> getter = HEADER_GETTERS.get(header);
    when(getter.apply(context)).thenReturn(headerValue);

    InetAddress expected = result != null ? InetAddress.getByName(result) : null;
    assertEquals(expected, ClientIpAddressResolver.resolve(context, span));
    getter.apply(verify(context));
  }

  @Test
  void testRecognitionStrategyWithCustomHeader() throws UnknownHostException {
    MutableSpan span = mock(MutableSpan.class);
    Extracted context = mock(Extracted.class);
    when(context.getCustomIpHeader()).thenReturn("8.8.8.8");

    // when
    InetAddress ip = ClientIpAddressResolver.resolve(context, span);

    // then
    verify(context).getCustomIpHeader();
    verifyNoMoreInteractions(context);
    assertEquals(InetAddress.getByName("8.8.8.8"), ip);
  }

  @Test
  void testRecognitionStrategyWithoutCustomHeader() throws UnknownHostException {
    MutableSpan span = mock(MutableSpan.class);
    Extracted context = mock(Extracted.class);
    when(context.getXRealIp()).thenReturn("127.0.0.1");
    when(context.getCfConnectingIpv6()).thenReturn("2001::1");

    // when
    InetAddress ip = ClientIpAddressResolver.resolve(context, span);

    // then
    InOrder inOrder = inOrder(context);
    inOrder.verify(context).getCustomIpHeader();
    inOrder.verify(context).getXForwardedFor();
    inOrder.verify(context).getXRealIp();
    inOrder.verify(context).getTrueClientIp();
    inOrder.verify(context).getXClientIp();
    inOrder.verify(context).getForwarded();
    inOrder.verify(context).getForwardedFor();
    inOrder.verify(context).getXClusterClientIp();
    inOrder.verify(context).getFastlyClientIp();
    inOrder.verify(context).getCfConnectingIp();
    inOrder.verify(context).getCfConnectingIpv6();
    verifyNoMoreInteractions(context, span);
    assertEquals(InetAddress.getByName("2001::1"), ip);
  }

  @Test
  void noCustomHeaderPublicIpAddressIsPreferred() throws UnknownHostException {
    MutableSpan span = mock(MutableSpan.class);
    Extracted context = mock(Extracted.class);
    when(context.getXRealIp()).thenReturn("127.0.0.1");
    when(context.getXClientIp()).thenReturn("8.8.8.8");

    // when
    InetAddress ip = ClientIpAddressResolver.resolve(context, span);

    // then
    verifyCalledAfterCustomIpHeader(context, Extracted::getXRealIp);
    verifyCalledAfterCustomIpHeader(context, Extracted::getXClientIp);
    assertEquals(InetAddress.getByName("8.8.8.8"), ip);
  }

  @Test
  void noCustomHeaderAllHeadersAreReported() throws UnknownHostException {
    MutableSpan span = mock(MutableSpan.class);
    Extracted context = mock(Extracted.class);
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

    // when
    InetAddress ip = ClientIpAddressResolver.resolve(context, span);

    // then
    for (Function<Extracted, String> getter : HEADER_GETTERS.values()) {
      verifyCalledAfterCustomIpHeader(context, getter);
    }
    assertEquals(InetAddress.getByName("127.0.0.1"), ip);
  }

  /** Verifies the header getter was called exactly once, after the custom IP header lookup. */
  private static void verifyCalledAfterCustomIpHeader(
      Extracted context, Function<Extracted, String> getter) {
    InOrder inOrder = inOrder(context);
    inOrder.verify(context).getCustomIpHeader();
    getter.apply(inOrder.verify(context));
  }
}
