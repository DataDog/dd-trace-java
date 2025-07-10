package com.datadog.iast;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.function.BiConsumer;

public class HttpRouteHandler implements BiConsumer<RequestContext, String> {

  @Override
  public void accept(final RequestContext ctx, final String route) {
    final IastRequestContext iastCtx = ctx.getData(RequestContextSlot.IAST);
    if (iastCtx != null) {
      iastCtx.setRoute(route);
    }
  }
}
