package datadog.trace.api.gateway

import spock.lang.Specification

class CallbackProviderNoopTest extends Specification {
  void 'get callback on noop implementation'() {
    setup:
    def cbp = CallbackProvider.CallbackProviderNoop.INSTANCE

    when:
    def cb = cbp.getCallback(Events.get().requestHeaderDone())

    then:
    cb == null
  }
}
