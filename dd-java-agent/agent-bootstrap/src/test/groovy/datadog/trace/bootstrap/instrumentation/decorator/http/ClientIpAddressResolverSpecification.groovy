package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import spock.lang.Specification

class ClientIpAddressResolverSpecification extends Specification {

  void 'test with custom header value=#headerValue'() {
    setup:
    MutableSpan span = Stub()
    def context = Mock(AgentSpanContext.Extracted)
    1 * context.getCustomIpHeader() >> headerValue

    expect:
    InetAddress resultInetAddr = result ? InetAddress.getByName(result) : null
    ClientIpAddressResolver.resolve(context, span) == resultInetAddr

    where:
    headerValue | result
    '127.0.0.1, 8.8.8.8' | '8.8.8.8'
    'for="[::1]", for=8.8.8.8' | '8.8.8.8'
    'for="8.8.8.8:8888"' | '8.8.8.8'
    'for="[::1]", for=[::ffff:1.1.1.1]:8888' | '1.1.1.1' // though we allow this, the correct form is the next
    'for="[::1]", for="[::ffff:1.1.1.1]:8888"' | '1.1.1.1'
    '10.0.0.1' | '10.0.0.1'
  }

  private static String headerToCamelCase(String headerName) {
    headerName.split('-').collect {it.capitalize()}.join()
  }

  void 'test with standard header #header=#headerValue'() {
    setup:
    MutableSpan span = Stub()
    def method = "get${headerToCamelCase(header)}"
    def context = Mock(AgentSpanContext.Extracted)
    1 * context."$method"() >> headerValue

    expect:
    InetAddress resultInetAddr = result ? InetAddress.getByName(result) : null
    ClientIpAddressResolver.resolve(context, span) == resultInetAddr

    where:
    header | headerValue | result
    'x-forwarded-for' | '2001:0::1' | '2001::1'
    'x-forwarded-for' | '::1, febf::1, fc00::1, fd00::1,2001:0000::1' | '2001::1'
    'x-forwarded-for' | 'fec0::,feff::ffff,fd00::,fdff::ffff,2001::1' | '2001::1'
    'x-forwarded-for' | '172.16.0.1' | '172.16.0.1'
    'x-forwarded-for' | '172.16.0.1, 172.31.255.254, 172.32.255.1, 8.8.8.8' | '172.32.255.1'
    'x-forwarded-for' | '169.254.0.1, 127.1.1.1, 10.255.255.254,' | '169.254.0.1'
    'x-forwarded-for' | '127.1.1.1,, ' | '127.1.1.1'
    'x-forwarded-for' | 'bad_value, 1.1.1.1' | '1.1.1.1'

    'x-real-ip' | '2.2.2.2' | '2.2.2.2'
    'x-real-ip' | '2.2.2.2, 3.3.3.3' | '2.2.2.2'
    'x-real-ip' | '127.0.0.1' | '127.0.0.1'
    'x-real-ip' | '::ffff:4.4.4.4' | '4.4.4.4'
    'x-real-ip' | '::ffff:127.0.0.1' | '127.0.0.1'
    'x-real-ip' | '42' | '0.0.0.42'

    'x-client-ip' | '2.2.2.2' | '2.2.2.2'

    'x-cluster-client-ip' | '2.2.2.2' | '2.2.2.2'

    'forwarded-for' | '::1, 127.0.0.1, 2001::1' | '2001::1'

    'true-client-ip' | '8.8.8.8' | '8.8.8.8'

    'fastly-client-ip' | '3.3.3.3' | '3.3.3.3'
    'cf-connecting-ip' | '4.4.4.4' | '4.4.4.4'
    'cf-connecting-ipv6' | '2001::2' | '2001::2'

    'forwarded' | 'for="[2001::1]:1111"' | '2001::1'
    'forwarded' | 'fOr="[2001::1]:1111"' | '2001::1'
    'forwarded' | 'for=some_host' | null
    'forwarded' | 'for=127.0.0.1, FOR=1.1.1.1' | '1.1.1.1'
    'forwarded' |'for="\"foobar";proto=http,FOR="1.1.1.1"' | '1.1.1.1'
    'forwarded' | 'for="8.8.8.8:2222",' | '8.8.8.8'
    'forwarded' | 'for="8.8.8.8' | null  // quote not closed
    'forwarded' | 'far="8.8.8.8",for=4.4.4.4;' | '4.4.4.4'
    'forwarded' | '   for=127.0.0.1,for= for=,for=;"for = for="" ,; for=8.8.8.8;' | '8.8.8.8'
    'forwarded' | 'for=192.0.2.60;proto=http;by=203.0.113.43' | '192.0.2.60'
    'forwarded' | 'For="[2001:db8:cafe::17]:4711"' | '2001:db8:cafe::17'
    'forwarded' | 'for=192.0.2.43;proto=https;by=203.0.113.43' | '192.0.2.43'
    'forwarded' | 'for="_gazonk"' | null
    'forwarded' | 'for=unknown, for=8.8.8.8' | '8.8.8.8'
    'forwarded' | 'for="[::ffff:192.0.2.128]";proto=http' | '192.0.2.128'
  }

  void 'test recognition strategy with custom header'() {
    setup:
    MutableSpan span = Stub()
    def context = Mock(AgentSpanContext.Extracted)

    when:
    def ip = ClientIpAddressResolver.resolve(context, span)

    then:
    1 * context.getCustomIpHeader() >> '8.8.8.8'
    0 * _

    ip == InetAddress.getByName('8.8.8.8')
  }

  void 'test recognition strategy without custom header'() {
    setup:
    MutableSpan span = Mock()
    def context = Mock(AgentSpanContext.Extracted)

    when:
    def ip = ClientIpAddressResolver.resolve(context, span)

    then:
    1 * context.getCustomIpHeader() >> null

    then:
    1 * context.getXForwardedFor() >> null

    then:
    1 * context.getXRealIp() >> '127.0.0.1'

    then:
    1 * context.getTrueClientIp() >> null

    then:
    1 * context.getXClientIp() >> null

    then:
    1 * context.getForwarded() >> null

    then:
    1 * context.getForwardedFor() >> null

    then:
    1 * context.getXClusterClientIp() >> null

    then:
    1 * context.getFastlyClientIp() >> null

    then:
    1 * context.getCfConnectingIp() >> null

    then:
    1 * context.getCfConnectingIpv6() >> '2001::1'
    0 * _

    ip == InetAddress.getByName('2001::1')
  }

  void 'no custom header public IP address is preferred'() {
    setup:
    MutableSpan span = Mock()
    def context = Mock(AgentSpanContext.Extracted)

    when:
    def ip = ClientIpAddressResolver.resolve(context, span)

    then:
    1 * context.getCustomIpHeader() >> null

    then:
    1 * context.getXRealIp() >> '127.0.0.1'
    1 * context.getXClientIp() >> '8.8.8.8'

    ip == InetAddress.getByName('8.8.8.8')
  }

  void 'no custom header all headers are reported'() {
    setup:
    MutableSpan span = Mock()
    def context = Mock(AgentSpanContext.Extracted)

    when:
    def ip = ClientIpAddressResolver.resolve(context, span)

    then:
    1 * context.getCustomIpHeader() >> null

    then:
    1 * context.getXForwardedFor() >> '127.0.0.1'
    1 * context.getXRealIp() >> '127.0.0.2'
    1 * context.getXClientIp() >> '127.0.0.3'
    1 * context.getForwarded() >> 'for=127.0.0.4'
    1 * context.getXClusterClientIp() >> '127.0.0.5'
    1 * context.getForwardedFor() >> '127.0.0.6'
    1 * context.getTrueClientIp() >> '127.0.0.9'
    1 * context.getFastlyClientIp() >> '127.0.0.10'
    1 * context.getCfConnectingIp() >> '127.0.0.11'
    1 * context.getCfConnectingIpv6() >> '::1'

    ip == InetAddress.getByName('127.0.0.1')
  }
}
