package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

@CompileDynamic
class HttpRouteHandlerTest extends DDSpecification {
  void 'route is set'() {
    given:
    final handler = new HttpRouteHandler()
    final iastCtx = Mock(IastRequestContext)
    final ctx = Mock(RequestContext)
    ctx.getData(RequestContextSlot.IAST) >> iastCtx

    when:
    handler.accept(ctx, '/foo')

    then:
    1 * ctx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * iastCtx.setRoute('/foo')
    0 * _
  }

  void 'does nothing when context missing'() {
    given:
    final handler = new HttpRouteHandler()
    final ctx = Mock(RequestContext)
    ctx.getData(RequestContextSlot.IAST) >> null

    when:
    handler.accept(ctx, '/foo')

    then:
    1 * ctx.getData(RequestContextSlot.IAST) >> null
    0 * _
  }
}
