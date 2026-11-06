package datadog.trace.bootstrap.instrumentation.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
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

  private static InetAddress address(String ip) throws UnknownHostException {
    return ip == null ? null : InetAddress.getByName(ip);
  }

  private static TagContext context(String forwarded, String custom) {
    TagContext.HttpHeaders headers = new TagContext.HttpHeaders();
    headers.xForwardedFor = forwarded;
    headers.customIpHeader = custom;
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
