package datadog.trace.api.http

import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import spock.lang.Specification

import java.util.function.BiFunction

class StoredCharBodyTest extends Specification {
  RequestContext requestContext = Mock(RequestContext) {
    _ * getData(_) >> it
  }
  BiFunction<RequestContext, StoredBodySupplier, Void> startCb = Mock()
  BiFunction<RequestContext, StoredBodySupplier, Flow<Void> > endCb = Mock()

  StoredCharBody storedCharBody = new StoredCharBody(requestContext, startCb, endCb, 1)

  void 'basic test with no buffer extension'() {
    Flow flow = Mock()

    when:
    storedCharBody.appendData('a')

    then:
    1 * startCb.apply(requestContext, storedCharBody)

    when:
    storedCharBody.appendData((int) 'a')
    storedCharBody.appendData(['a' as char]* 126 as char[], 0, 126)
    def resFlow = storedCharBody.maybeNotify()

    then:
    1 * endCb.apply(requestContext, storedCharBody) >> flow
    storedCharBody.get().toString() == 'a' * 128
    resFlow.is(flow)
  }

  void 'has a cutoff at 128k chars'() {
    when:
    storedCharBody.appendData('a')
    storedCharBody.appendData('a' * (128 * 1024)) // last ignored
    storedCharBody.appendData((int) 'a') // ignored
    storedCharBody.appendData('a') // ignored
    storedCharBody.appendData(['a' as char] as char[], 0, 1) // ignored

    then:
    1 * startCb.apply(requestContext, storedCharBody)
  }

  void 'insert invalid data'() {
    when:
    storedCharBody.appendData(-1)

    then:
    storedCharBody.get() as String == ''
  }

  void 'insert empty range'() {
    when:
    storedCharBody.appendData([] as char[], 0, 0)

    then:
    storedCharBody.get() as String == ''
  }

  void 'exercise maybeNotify and get on empty object'() {
    when:
    storedCharBody.maybeNotify()

    then:
    1 * startCb.apply(requestContext, storedCharBody)
    then:
    1 * endCb.apply(requestContext, storedCharBody)
    then:
    storedCharBody.get() as String == ''
  }
}
