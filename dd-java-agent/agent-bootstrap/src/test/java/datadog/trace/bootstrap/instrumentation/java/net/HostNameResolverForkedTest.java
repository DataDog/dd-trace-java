package datadog.trace.bootstrap.instrumentation.java.net;

import static datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver.getAlreadyResolvedHostName;
import static datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver.hostName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HostNameResolverForkedTest {
  /** IPv6 loopback address (::1). */
  private static final byte[] IPV6_LOOPBACK = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

  @ParameterizedTest(name = "{0}")
  @MethodSource("shouldDirectlyGetTheHostnameForAlreadyResolvedAddressArguments")
  void shouldDirectlyGetTheHostnameForAlreadyResolvedAddress(
      String scenario,
      Class<? extends InetAddress> addressType,
      InetAddress address,
      String expected) {
    assertInstanceOf(addressType, address);

    String host = getAlreadyResolvedHostName(address);

    assertEquals(expected, host);
  }

  static Stream<Arguments> shouldDirectlyGetTheHostnameForAlreadyResolvedAddressArguments()
      throws UnknownHostException {
    return Stream.of(
        arguments(
            "resolved IPv4 address",
            Inet4Address.class,
            InetAddress.getByAddress("test", InetAddress.getLocalHost().getAddress()),
            "test"),
        arguments(
            "resolved IPv6 address",
            Inet6Address.class,
            InetAddress.getByAddress("test", IPV6_LOOPBACK),
            "test"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shouldReturnNullWhenDirectlyGetTheAddressForUnresolvedArguments")
  void shouldReturnNullWhenDirectlyGetTheAddressForUnresolved(
      String scenario, Class<? extends InetAddress> addressType, InetAddress address) {
    assertInstanceOf(addressType, address);

    String host = getAlreadyResolvedHostName(address);

    assertNull(host);
  }

  static Stream<Arguments> shouldReturnNullWhenDirectlyGetTheAddressForUnresolvedArguments()
      throws UnknownHostException {
    return Stream.of(
        arguments(
            "unresolved IPv4 address",
            Inet4Address.class,
            InetAddress.getByAddress(InetAddress.getLocalHost().getAddress())),
        arguments(
            "unresolved IPv6 address",
            Inet6Address.class,
            InetAddress.getByAddress(IPV6_LOOPBACK)));
  }

  @Test
  void shouldUseTheCacheForUnresolvedAddresses() throws UnknownHostException {
    InetAddress inet1 = InetAddress.getByAddress(InetAddress.getLocalHost().getAddress());
    // this will fail if a resolution will happen
    InetAddress inet2 = InetAddress.getByAddress(new byte[4]);

    // when
    InetSocketAddress address = new InetSocketAddress(inet1, 666);
    String host = hostName(address.getAddress(), "127.0.0.1");

    // then
    assertNotNull(host);

    // when
    address = new InetSocketAddress(inet2, 666);
    String host2 = hostName(address.getAddress(), "127.0.0.1");

    // then
    assertEquals(host, host2);
  }
}
