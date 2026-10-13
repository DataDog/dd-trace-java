package datadog.trace.instrumentation.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

// Forked test: runs in an isolated JVM with JPMS instrumentation DISABLED.
// --illegal-access=deny is only enforced from Java 16 onward.
@EnabledForJreRange(min = JRE.JAVA_16)
@WithConfig(key = "trace.java-module.enabled", value = "false")
class JpmsInetAddressDisabledForkedTest extends AbstractInstrumentationTest {

  /**
   * Verifies the fallback behaviour when the JPMS instrumentation is disabled: HostNameResolver
   * cannot reflectively read the pre-set hostname from InetAddress and falls back to a cache keyed
   * by IP address. As a result, once a hostname has been cached for a given IP, every subsequent
   * lookup for that IP returns the first cached value, even when the InetAddress object carries a
   * different hostname.
   *
   * <p>This is the broken behaviour that the JPMS instrumentation is designed to fix.
   */
  @Test
  void withoutJpmsInstrumentationIpCausesStaleHostnameToBeReturned() throws Exception {
    assumeFalse(JavaVirtualMachine.isJ9(), "Does not work on J9");
    // different subnet from the enabled-test to avoid cross-test cache pollution
    byte[] ip = {(byte) 192, 0, 2, 2};
    InetAddress addr1 = InetAddress.getByAddress("service1.example.com", ip);
    // Prime the IP→hostname cache with service1's hostname
    HostNameResolver.hostName(addr1, "192.0.2.2");

    // a second service with the same IP but a different hostname is resolved
    InetAddress addr2 = InetAddress.getByAddress("service2.example.com", ip);
    String result = HostNameResolver.hostName(addr2, "192.0.2.2");

    // the stale cached hostname of service1 is returned instead of service2's
    assertEquals("service1.example.com", result);
  }
}
