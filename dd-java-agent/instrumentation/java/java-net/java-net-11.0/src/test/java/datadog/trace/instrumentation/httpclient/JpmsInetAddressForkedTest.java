package datadog.trace.instrumentation.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

// Forked test: runs in an isolated JVM with JPMS instrumentation ENABLED (default).
class JpmsInetAddressForkedTest extends AbstractInstrumentationTest {

  /**
   * Verifies that the JPMS instrumentation opens java.base/java.net so that HostNameResolver can
   * bypass its IP→hostname cache and return the correct peer.hostname even when multiple services
   * share a single IP address (e.g. services behind a reverse proxy).
   *
   * <p>Without the fix, HostNameResolver cannot reflectively access InetAddress$InetAddressHolder
   * on Java 9+ and falls back to a cache keyed by IP, causing the first service's hostname to be
   * returned for all subsequent services on the same IP.
   */
  @Test
  void instrumentationOpensJavaNetSoHostnameIsResolvedCorrectlyWhenIpIsShared() throws Exception {
    assumeFalse(JavaVirtualMachine.isJ9(), "Does not work on J9");

    // emulate an early initialisation
    HostNameResolver.hostName(null, "192.0.2.1");
    byte[] ip = {(byte) 192, 0, 2, 1}; // TEST-NET, will never appear in real DNS cache
    InetAddress addr1 = InetAddress.getByAddress("service1.example.com", ip);
    // Warm the IP→hostname cache with service1's hostname
    HostNameResolver.hostName(addr1, "192.0.2.1");

    // a second service with the same IP but different hostname is resolved
    InetAddress addr2 = InetAddress.getByAddress("service2.example.com", ip);
    String result = HostNameResolver.hostName(addr2, "192.0.2.1");

    // the hostname of addr2 is returned, not the cached hostname of addr1
    assertEquals("service2.example.com", result);
  }
}
