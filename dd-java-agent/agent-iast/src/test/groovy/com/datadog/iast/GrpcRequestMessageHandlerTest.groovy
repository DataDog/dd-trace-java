package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.test.util.DDSpecification

class GrpcRequestMessageHandlerTest extends DDSpecification {

  private PropagationModule propagation
  private IastRequestContext iastCtx
  private RequestContext ctx

  void setup() {
    propagation = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagation)
    iastCtx = Mock(IastRequestContext)
    ctx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> iastCtx
    }
  }

  void 'the handler does nothing without propagation'() {
    given:
    final handler = new GrpcRequestMessageHandler()
    InstrumentationBridge.clearIastModules()

    when:
    handler.apply(ctx, [:])

    then:
    0 * _
  }

  void 'the handler does nothing with null values'() {
    given:
    final handler = new GrpcRequestMessageHandler()

    when:
    handler.apply(ctx, null)

    then:
    0 * _
  }

  void 'the handler forwards objects to the propagation module'() {
    given:
    final target = [:]
    final handler = new GrpcRequestMessageHandler()

    when:
    handler.apply(ctx, target)

    then:
    1 * propagation.taintDeeply(iastCtx, SourceTypes.GRPC_BODY, target)
  }
}
