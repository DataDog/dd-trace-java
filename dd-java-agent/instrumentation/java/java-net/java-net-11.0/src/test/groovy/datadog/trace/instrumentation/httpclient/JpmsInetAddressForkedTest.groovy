package datadog.trace.instrumentation.httpclient

import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver
import spock.lang.IgnoreIf

@IgnoreIf(reason =  'Does not work on J9', value = {
  JavaVirtualMachine.isJ9()
})
class JpmsInetAddressForkedTest extends InstrumentationSpecification {

  /**
   * Verifies that the JPMS instrumentation opens java.base/java.net so that
   * HostNameResolver can bypass its IP→hostname cache and return the correct
   * peer.hostname even when multiple services share a single IP address
   * (e.g. services behind a reverse proxy).
   *
   * Without the fix, HostNameResolver cannot reflectively access
   * InetAddress$InetAddressHolder on Java 9+ and falls back to a cache keyed
   * by IP, causing the first service's hostname to be returned for all
   * subsequent services on the same IP.
   */
  def "instrumentation opens java.net so hostname is resolved correctly when IP is shared"() {
    given:
    // emulate an early initialisation
    HostNameResolver.hostName(null, "192.0.2.1")
    def ip = [192, 0, 2, 1] as byte[]  // TEST-NET, will never appear in real DNS cache
    def addr1 = InetAddress.getByAddress("service1.example.com", ip)
    // Warm the IP→hostname cache with service1's hostname
    HostNameResolver.hostName(addr1, "192.0.2.1")

    when: "a second service with the same IP but different hostname is resolved"
    def addr2 = InetAddress.getByAddress("service2.example.com", ip)
    def result = HostNameResolver.hostName(addr2, "192.0.2.1")

    then: "the hostname of addr2 is returned, not the cached hostname of addr1"
    result == "service2.example.com"
  }
}
