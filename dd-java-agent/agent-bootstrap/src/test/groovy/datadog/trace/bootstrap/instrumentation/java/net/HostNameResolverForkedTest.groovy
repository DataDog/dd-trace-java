package datadog.trace.bootstrap.instrumentation.java.net

import datadog.trace.test.util.DDSpecification

class HostNameResolverForkedTest extends DDSpecification {
  def "should directly get the hostname for already resolved address #address"() {
    given:
    def host = HostNameResolver.getAlreadyResolvedHostName(address)
    expect:
    host == expected
    where:
    address                                                           | expected
    new Inet4Address("test", InetAddress.getLocalHost().getAddress()) | "test"
    new Inet6Address("test", InetAddress.getLocalHost().getAddress()) | "test"
  }

  def "should return null when directly get the address for unresolved #address"() {
    given:
    def host = HostNameResolver.getAlreadyResolvedHostName(address)
    expect:
    host == null
    where:
    address                                                           | _
    InetAddress.getByAddress(InetAddress.getLocalHost().getAddress()) | _
    new Inet6Address(null, InetAddress.getLocalHost().getAddress())   | _
  }

  def "should use the cache for unresolved addresses"() {
    given:
    def inet1 = new Inet4Address(null, InetAddress.getLocalHost().getAddress())
    def inet2 = new Inet4Address(null, 0) // this will fail if a resolution will happen
    when:
    def address = new InetSocketAddress(inet1, 666)
    def host = HostNameResolver.hostName(address.getAddress(), "127.0.0.1")
    then:
    host != null
    when:
    address = new InetSocketAddress(inet2, 666)
    def host2 = HostNameResolver.hostName(address.getAddress(), "127.0.0.1")
    then:
    host == host2
  }
}
