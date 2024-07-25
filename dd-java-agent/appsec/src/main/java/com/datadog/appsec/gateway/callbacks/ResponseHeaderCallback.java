package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

public class ResponseHeaderCallback implements TriConsumer<RequestContext, String, String> {

  @Override
  public void accept(RequestContext requestContext, String name, String value) {
    AppSecRequestContext ctx = requestContext.getData(RequestContextSlot.APPSEC);
    if (ctx != null) {
      ctx.addResponseHeader(name, value);
    }
  }
}
