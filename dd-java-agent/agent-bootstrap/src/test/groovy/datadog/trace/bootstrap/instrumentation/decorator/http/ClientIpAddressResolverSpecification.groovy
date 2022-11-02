package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Specification

class ClientIpAddressResolverSpecification extends Specification {

  void 'test with custom header value=#headerValue'() {
    setup:
    MutableSpan span = Stub()
    def context = Mock(AgentSpan.Context.Extracted)
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
    def context = Mock(AgentSpan.Context.Extracted)
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

    'client-ip' | '2.2.2.2' | '2.2.2.2'
    'x-forwarded' | 'for="[2001::1]:1111"' | '2001::1'
    'x-forwarded' | 'fOr="[2001::1]:1111"' | '2001::1'
    'x-forwarded' | 'for=some_host' | null
    'x-forwarded' | 'for=127.0.0.1, FOR=1.1.1.1' | '1.1.1.1'
    'x-forwarded' |'for="\"foobar";proto=http,FOR="1.1.1.1"' | '1.1.1.1'
    'x-forwarded' | 'for="8.8.8.8:2222",' | '8.8.8.8'
    'x-forwarded' | 'for="8.8.8.8' | null  // quote not closed
    'x-forwarded' | 'far="8.8.8.8",for=4.4.4.4;' | '4.4.4.4'
    'x-forwarded' | '   for=127.0.0.1,for= for=,for=;"for = for="" ,; for=8.8.8.8;' | '8.8.8.8'

    'x-cluster-client-ip' | '2.2.2.2' | '2.2.2.2'

    'forwarded-for' | '::1, 127.0.0.1, 2001::1' | '2001::1'

    'forwarded' | 'for=8.8.8.8' | '8.8.8.8'

    'via' | '1.0 127.0.0.1, HTTP/1.1 [2001::1]:8888' | '2001::1'
    'via' | 'HTTP/1.1 [2001::1, HTTP/1.1 [2001::2]' | '2001::2'
    'via' | '8.8.8.8' | null
    'via' | '8.8.8.8, 1.0 9.9.9.9:8888,' | '9.9.9.9'
    'via' | '1.0 bad_ip_address, 1.0 9.9.9.9:8888,' | '9.9.9.9'
    'via' | ",,8.8.8.8  127.0.0.1 6.6.6.6, 1.0\t  1.1.1.1\tcomment," | '1.1.1.1'

    'true-client-ip' | '8.8.8.8' | '8.8.8.8'
  }

  void 'test recognition strategy with custom header'() {
    setup:
    MutableSpan span = Stub()
    def context = Mock(AgentSpan.Context.Extracted)

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
    def context = Mock(AgentSpan.Context.Extracted)

    when:
    def ip = ClientIpAddressResolver.resolve(context, span)

    then:
    1 * context.getCustomIpHeader() >> null

    then:
    1 * context.getXForwardedFor() >> null

    then:
    1 * context.getXRealIp() >> null

    then:
    1 * context.getClientIp() >> null

    then:
    1 * context.getXForwarded() >> null

    then:
    1 * context.getXClusterClientIp() >> null

    then:
    1 * context.getForwardedFor() >> null

    then:
    1 * context.getForwarded() >> null

    then:
    1 * context.getVia() >> null

    then:
    1* context.getTrueClientIp() >> '8.8.8.8'
    0 * _

    ip == InetAddress.getByName('8.8.8.8')
  }

  void 'no custom header public IP address is preferred'() {
    setup:
    MutableSpan span = Mock()
    def context = Mock(AgentSpan.Context.Extracted)

    when:
    def ip = ClientIpAddressResolver.resolve(context, span)

    then:
    1 * context.getCustomIpHeader() >> null

    then:
    1 * context.getXRealIp() >> '127.0.0.1'
    1 * context.getClientIp() >> '8.8.8.8'
    1 * context.getXClusterClientIp() >> '127.0.0.2'

    then:
    1 * span.setTag('_dd.multiple-ip-headers', 'x-cluster-client-ip,client-ip,x-real-ip')

    ip == InetAddress.getByName('8.8.8.8')
  }

  void 'no custom header all headers are reported'() {
    setup:
    MutableSpan span = Mock()
    def context = Mock(AgentSpan.Context.Extracted)

    when:
    def ip = ClientIpAddressResolver.resolve(context, span)

    then:
    1 * context.getCustomIpHeader() >> null

    then:
    1 * context.getXForwardedFor() >> '127.0.0.1'
    1 * context.getXRealIp() >> '127.0.0.2'
    1 * context.getClientIp() >> '127.0.0.3'
    1 * context.getXForwarded() >> 'for=127.0.0.4'
    1 * context.getXClusterClientIp() >> '127.0.0.5'
    1 * context.getForwardedFor() >> '127.0.0.6'
    1 * context.getForwarded() >> 'for=127.0.0.7'
    1 * context.getVia() >> '1.0 127.0.0.8'
    1 * context.getTrueClientIp() >> '127.0.0.9'

    then:
    1 * span.setTag('_dd.multiple-ip-headers',
      'true-client-ip,via,forwarded,forwarded-for,x-cluster-client-ip,x-forwarded,client-ip,x-real-ip,x-forward-for')

    ip == InetAddress.getByName('127.0.0.9')
  }
}
