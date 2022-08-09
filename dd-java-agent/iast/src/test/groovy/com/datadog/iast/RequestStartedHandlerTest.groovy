package com.datadog.iast

import com.datadog.iast.overhead.OverheadController
import datadog.trace.api.gateway.Flow
import datadog.trace.test.util.DDSpecification

class RequestStartedHandlerTest extends DDSpecification {

  void 'request starts successfully'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    overheadController.acquireRequest() >> true
    def handler = new RequestStartedHandler(overheadController)

    when:
    def flow = handler.get()

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() instanceof IastRequestContext
    1 * overheadController.acquireRequest() >> true
    0 * _
  }

  void 'request start cannot acquire'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    overheadController.acquireRequest() >> false
    def handler = new RequestStartedHandler(overheadController)

    when:
    def flow = handler.get()

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * overheadController.acquireRequest() >> false
    0 * _
  }
}
