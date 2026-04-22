package datadog.trace.instrumentation.httpclient

import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.java.net.HostNameResolver
import spock.lang.IgnoreIf

@IgnoreIf(reason = "--illegal-access=deny is only enforced from java 16", value = {
  !JavaVirtualMachine.isJavaVersionAtLeast(16)
})
class JpmsInetAddressDisabledForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Disable the JPMS instrumentation so java.net is NOT opened for deep reflection.
    // HostNameResolver will be unable to bypass the IP→hostname cache and will fall back
    // to the cache keyed by IP address.
    injectSysConfig("dd.trace.java-net.enabled", "false")
  }

  /**
   * Verifies the fallback behaviour when the JPMS instrumentation is disabled:
   * HostNameResolver cannot reflectively read the pre-set hostname from InetAddress and
   * falls back to a cache keyed by IP address.  As a result, once a hostname has been
   * cached for a given IP, every subsequent lookup for that IP returns the first cached
   * value, even when the InetAddress object carries a different hostname.
   *
   * This is the broken behaviour that the JPMS instrumentation is designed to fix.
   */
  def "without JPMS instrumentation, IP cache causes stale hostname to be returned"() {
    given:
    def ip = [192, 0, 2, 2] as byte[]  // different subnet from the enabled-test to avoid cross-test cache pollution
    def addr1 = InetAddress.getByAddress("service1.example.com", ip)
    // Prime the IP→hostname cache with service1's hostname
    HostNameResolver.hostName(addr1, "192.0.2.2")

    when: "a second service with the same IP but a different hostname is resolved"
    def addr2 = InetAddress.getByAddress("service2.example.com", ip)
    def result = HostNameResolver.hostName(addr2, "192.0.2.2")

    then: "the stale cached hostname of service1 is returned instead of service2's"
    result == "service1.example.com"
  }
}
