package datadog.trace.api.gateway

import datadog.trace.api.function.Function
import spock.lang.Specification

class SubscriptionServiceNoopTest extends Specification {
  void 'registration on noop implementation'() {
    setup:
    def ss = SubscriptionService.SubscriptionServiceNoop.INSTANCE
    Function<RequestContext, Flow<Void>> cb = Mock()

    when:
    def sub = ss.registerCallback(Events.get().requestHeaderDone(), cb)
    sub.cancel()

    then:
    0 * cb._(*_)
  }
}
