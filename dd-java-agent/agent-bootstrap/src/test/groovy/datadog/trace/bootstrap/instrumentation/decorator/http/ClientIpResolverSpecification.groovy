package datadog.trace.bootstrap.instrumentation.decorator.http

import spock.lang.Specification

class ClientIpResolverSpecification extends Specification {
  private static final String HEADER = 'foo-bar'

  void 'test with configured header'() {
    expect:
    InetAddress resultInetAddr = result ? InetAddress.getByName(result) : null
    ClientIpResolver.resolve(HEADER, [(HEADER): [headerValue]]) == resultInetAddr

    where:
    headerValue | result
    '127.0.0.1, 8.8.8.8' | '8.8.8.8'
    'for="[::1]", for=8.8.8.8' | '8.8.8.8'
    'for="8.8.8.8:8888"' | '8.8.8.8'
    'for="[::1]", for=[::ffff:1.1.1.1]:8888' | '1.1.1.1' // though we allow this, the correct form is the next
    'for="[::1]", for="[::ffff:1.1.1.1]:8888"' | '1.1.1.1'
    '10.0.0.1' | null // peer address is ignored!
  }

  void 'test without configured header'() {
    expect:
    InetAddress resultInetAddr = result ? InetAddress.getByName(result) : null
    ClientIpResolver.resolve(null, [(header): [headerValue]]) == resultInetAddr

    where:
    header | headerValue | result
    'x-forwarded-for' | '2001:0::1' | '2001::1'
    'x-forwarded-for' | '::1, febf::1, fc00::1, fd00::1,2001:0000::1' | '2001::1'
    'x-forwarded-for' | '172.16.0.1' | null
    'x-forwarded-for' | '172.16.0.1, 172.31.255.254, 172.32.255.1, 8.8.8.8' | '172.32.255.1'
    'x-forwarded-for' | '169.254.0.1, 127.1.1.1, 10.255.255.254,' | null
    'x-forwarded-for' | '127.1.1.1,, ' | null
    'x-forwarded-for' | 'bad_value, 1.1.1.1' | '1.1.1.1'

    'x-real-ip' | '2.2.2.2' | '2.2.2.2'
    'x-real-ip' | '2.2.2.2, 3.3.3.3' | '2.2.2.2'
    'x-real-ip' | '127.0.0.1' | null
    'x-real-ip' | '::ffff:4.4.4.4' | '4.4.4.4'
    'x-real-ip' | '::ffff:127.0.0.1' | null
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
}
