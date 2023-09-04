package com.datadog.appsec.gateway;

import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

class ResponseHeaderCallback implements TriConsumer<RequestContext, String, String> {
  @Override
  public void accept(final RequestContext ctx_, final String name, final String value) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx != null) {
      ctx.addResponseHeader(name, value);
    }
  }
}
