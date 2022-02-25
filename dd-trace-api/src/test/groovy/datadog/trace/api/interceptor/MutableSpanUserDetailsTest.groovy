package datadog.trace.api.interceptor

import spock.lang.Specification

class MutableSpanUserDetailsTest extends Specification {
  MutableSpan span = Mock()
  MutableSpanUserDetails userDetails = new MutableSpanUserDetails(span)

  void '#method method set the correct tag'() {
    when:
    def ret = userDetails."$method"('foobar')

    then:
    1 * span.setTag(tagName, 'foobar')
    ret.is(userDetails)

    where:
    method          | tagName
    'withEmail'     | 'usr.email'
    'withName'      | 'usr.name'
    'withSessionId' | 'usr.session_id'
    'withRole'      | 'usr.role'
  }

  void 'withCustomData correctly prefixes the tag'() {
    when:
    def ret = userDetails.withCustomData('foo', 'bar')

    then:
    ret.is(userDetails)
    1 * span.setTag('usr.foo', 'bar')
  }
}
