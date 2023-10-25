package com.datadog.iast

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

@CompileDynamic
class RequestHeaderHandlerTest extends DDSpecification {
  void 'forwarded proto is set'(){
    given:
    final handler = new RequestHeaderHandler()
    final iastCtx = Mock(IastRequestContext)
    final ctx = Mock(RequestContext)
    ctx.getData(RequestContextSlot.IAST) >> iastCtx

    when:
    handler.accept(ctx, 'X-Forwarded-Proto', 'https')

    then:
    1 * ctx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * iastCtx.setxForwardedProto('https')
    0 * _
  }


  void 'forwarded proto is not set'(){
    given:
    final handler = new RequestHeaderHandler()
    final iastCtx = Mock(IastRequestContext)
    final ctx = Mock(RequestContext)
    ctx.getData(RequestContextSlot.IAST) >> iastCtx

    when:
    handler.accept(ctx, 'Custom-Header', 'https')

    then:
    1 * ctx.getData(RequestContextSlot.IAST) >> iastCtx
    0 * iastCtx.getxForwardedProto()
    0 * _
  }
}
